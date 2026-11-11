package io.github.clarence404.assistant.state;

import io.github.clarence404.assistant.agent.ConfirmationStore;
import io.github.clarence404.assistant.guard.ConcurrencyLimiter;
import io.github.clarence404.assistant.guard.GuardProperties;
import io.github.clarence404.assistant.guard.RateLimiterFactory;
import io.github.clarence404.assistant.guard.SemaphoreConcurrencyLimiter;
import io.github.clarence404.assistant.memory.ConversationLocks;
import io.github.clarence404.assistant.memory.OwnerCache;
import io.github.clarence404.assistant.ops.UsageStore;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 共享状态的 Redis 实现（{@code assistant.state.store=redis}）：多个实例共用限流、预算、并发名额、待确认操作、会话标记与归属缓存（第 10 章引入）。
 *
 * <p>所在层：共享状态的装配。和 {@link MemoryStateConfig} 二选一。连接自己建（不用 Spring Boot 的 Redis Starter，原因见 pom.xml）：
 *
 * <ul>
 *   <li>{@link LettuceConnectionFactory}：Lettuce 是 Spring Data Redis 默认的客户端，基于 Netty，一条连接可以被所有线程共用（命令排队发出去、
 *       按顺序收回来），所以不用连接池。<b>启动时不连</b>（Lettuce 第一次用到时才建连接）：Redis 没启动，应用照样起得来，只是用到时按策略处理
 *   <li>命令超时 {@code assistant.state.redis.timeout}（500 毫秒）、连接超时 {@code connect-timeout}（1 秒）：Redis 慢了就当它挂了，不让请求卡住
 *   <li>断线时立刻拒绝命令（{@code REJECT_COMMANDS}）：Lettuce 默认会把命令攒着等重连，等到超时才失败；这里宁可马上失败、马上按策略处理
 * </ul>
 *
 * <p>没有用 Redisson、Spring Integration 的 RedisLockRegistry 这类现成的分布式锁 / 限流库：它们功能更全（看门狗自动续期、可重入、公平锁），
 * 但这一章要讲清楚的正是「几条命令 + 一段 Lua 怎么做到原子」，生产上可以换成它们。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "assistant.state.store", havingValue = "redis")
public class RedisStateConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisStateConfig.class);

    /**
     * 连接工厂：地址、库号、用户名与密码都从 {@code assistant.state.redis.url} 解析（{@code redis://[用户名]:[密码]@主机:端口/库号}）。
     *
     * @param properties {@code assistant.state.*}
     * @return 连接工厂；Spring 启动时调它的 {@code afterPropertiesSet} / {@code start}，关闭时 {@code destroy}
     */
    @Bean
    public LettuceConnectionFactory stateRedisConnectionFactory(StateProperties properties) {
        StateProperties.Redis redis = properties.redis();
        RedisStandaloneConfiguration server = server(redis.url());
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(redis.timeout())
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(redis.connectTimeout())
                                .build())
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build())
                .build();
        // 日志里只打主机、端口和库号，不打密码
        log.info(
                "共享状态存进 Redis：{}:{}，库 {}，键前缀 {}（Redis 出错时限流与预算的策略：{}）",
                server.getHostName(),
                server.getPort(),
                server.getDatabase(),
                redis.keyPrefix(),
                properties.onRedisError());
        return new LettuceConnectionFactory(server, client);
    }

    /**
     * 解析连接地址。用 JDK 的 {@link URI}：{@code redis://:密码@主机:6379/5} 里，密码在 userInfo（冒号前是用户名，Redis 6 起的 ACL 用户，没有就空着），
     * 库号是路径。
     *
     * @param url 连接地址
     * @return 单机 Redis 的配置
     */
    static RedisStandaloneConfiguration server(String url) {
        URI uri = URI.create(url);
        if (!"redis".equals(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("assistant.state.redis.url 要写成 redis://主机:端口/库号");
        }
        RedisStandaloneConfiguration server =
                new RedisStandaloneConfiguration(uri.getHost(), uri.getPort() < 0 ? 6379 : uri.getPort());
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            server.setDatabase(Integer.parseInt(path.substring(1)));
        }
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            String user = colon < 0 ? userInfo : userInfo.substring(0, colon);
            String password = colon < 0 ? "" : userInfo.substring(colon + 1);
            if (!user.isEmpty()) {
                server.setUsername(URLDecoder.decode(user, StandardCharsets.UTF_8));
            }
            if (!password.isEmpty()) {
                server.setPassword(RedisPassword.of(URLDecoder.decode(password, StandardCharsets.UTF_8)));
            }
        }
        return server;
    }

    /**
     * 读写字符串的模板：键、值、Lua 脚本的参数都按字符串处理，在 redis-cli 里看得懂。
     *
     * @param factory 连接工厂
     * @return 模板
     */
    @Bean
    public StringRedisTemplate stateRedisTemplate(LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 键前缀。
     *
     * @param properties {@code assistant.state.*}
     * @return 键
     */
    @Bean
    public RedisKeys stateRedisKeys(StateProperties properties) {
        return new RedisKeys(properties.redis().keyPrefix());
    }

    /**
     * 限流器：Redis 有序集合 + Lua 脚本，几个实例合计。
     *
     * @param redis 模板
     * @param keys 键
     * @param clock 时钟
     * @param failures 出错时怎么办
     * @return 工厂
     */
    @Bean
    public RateLimiterFactory redisRateLimiterFactory(
            StringRedisTemplate redis, RedisKeys keys, Clock clock, StateFailures failures) {
        return (name, limit, window) ->
                new RedisRateLimiter(redis, keys.of("rate", name), "rate:" + name, limit, window, clock, failures);
    }

    /**
     * 并发名额：分布式信号量；Redis 出错且策略是放行时，退回本实例的信号量。
     *
     * @param redis 模板
     * @param keys 键
     * @param guard {@code assistant.guard.*}（上限、等多久）
     * @param properties {@code assistant.state.*}（租期、轮询间隔）
     * @param clock 时钟
     * @param failures 出错时怎么办
     * @return 并发名额
     */
    @Bean
    public ConcurrencyLimiter redisConcurrencyLimiter(
            StringRedisTemplate redis,
            RedisKeys keys,
            GuardProperties guard,
            StateProperties properties,
            Clock clock,
            StateFailures failures) {
        return new RedisConcurrencyLimiter(
                redis,
                keys.of("slots", "chat"),
                guard::maxConcurrent,
                properties.lease(),
                properties.pollInterval(),
                clock,
                failures,
                new SemaphoreConcurrencyLimiter(guard.maxConcurrent()));
    }

    /**
     * Token 总账：按天分键的哈希与有序集合。
     *
     * @param redis 模板
     * @param keys 键
     * @param failures 出错时怎么办
     * @return 账
     */
    @Bean
    public UsageStore redisUsageStore(StringRedisTemplate redis, RedisKeys keys, StateFailures failures) {
        return new RedisUsageStore(redis, keys, failures);
    }

    /**
     * 待确认操作：JSON + {@code SET NX} 抢占。
     *
     * @param redis 模板
     * @param keys 键
     * @param failures 出错时怎么办（一律拒绝）
     * @return 存储
     */
    @Bean
    public ConfirmationStore redisConfirmationStore(StringRedisTemplate redis, RedisKeys keys, StateFailures failures) {
        return new RedisConfirmationStore(redis, keys, JsonMapper.builder().build(), failures);
    }

    /**
     * 「正在回答」标记：{@code SET NX PX} 的分布式锁。
     *
     * @param redis 模板
     * @param keys 键
     * @param properties {@code assistant.state.*}（租期）
     * @param failures 出错时怎么办
     * @return 标记
     */
    @Bean
    public ConversationLocks redisConversationLocks(
            StringRedisTemplate redis, RedisKeys keys, StateProperties properties, StateFailures failures) {
        return new RedisConversationLocks(redis, keys, properties.lease(), failures);
    }

    /**
     * 「令牌是谁」的缓存。
     *
     * @param redis 模板
     * @param keys 键
     * @param failures 出错时记一下
     * @return 缓存
     */
    @Bean
    public OwnerCache redisOwnerCache(StringRedisTemplate redis, RedisKeys keys, StateFailures failures) {
        return new RedisOwnerCache(redis, keys, failures);
    }
}
