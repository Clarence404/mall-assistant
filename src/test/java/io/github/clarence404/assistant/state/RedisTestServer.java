package io.github.clarence404.assistant.state;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 第 10 章集成测试用的 Redis：有 Docker 时 Testcontainers 起一个 {@value #IMAGE}（CI 上就是这样）；没有 Docker 时用环境变量
 * {@code REDIS_IT_URL} 指向一个现成的 Redis（如 {@code redis://:密码@主机:6379/15}，选一个没人用的库号）；两者都没有就跳过。
 *
 * <p>每个测试类拿一个随机的键前缀（{@code it-xxxxxxxx:}），结束时按前缀 {@code SCAN} 出来删掉——连共享的 Redis 也只删自己写的键，
 * 不用 {@code FLUSHDB}。{@link #client()} 每调一次就新建一条连接（一个新的 Lettuce 客户端），用来模拟「两个实例」。
 */
final class RedisTestServer implements AutoCloseable {

    /** 镜像版本写死（和开发机上 mall-infra 的 Redis 同一个版本），换版本时同步改讲义。 */
    static final String IMAGE = "redis:8.10.3-alpine";

    private static GenericContainer<?> container;
    private static String url;

    private final String prefix = "it-" + UUID.randomUUID().toString().substring(0, 8) + ":";
    private final List<LettuceConnectionFactory> factories = new ArrayList<>();

    /**
     * 连上 Redis（第一次调用时起容器或读环境变量）；都没有就跳过当前测试类。
     *
     * @return 测试用的 Redis
     */
    static synchronized RedisTestServer connect() {
        if (url == null) {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                container = new GenericContainer<>(DockerImageName.parse(IMAGE)).withExposedPorts(6379);
                container.start();
                url = "redis://" + container.getHost() + ":" + container.getMappedPort(6379) + "/0";
            } else {
                String env = System.getenv("REDIS_IT_URL");
                Assumptions.assumeTrue(env != null && !env.isBlank(), "没有 Docker，也没有设置 REDIS_IT_URL，跳过 Redis 的集成测试");
                url = env;
            }
        }
        return new RedisTestServer();
    }

    /** 连接地址（{@code assistant.state.redis.url} 的格式）。 */
    String url() {
        return url;
    }

    /** 这个测试类的键前缀。 */
    RedisKeys keys() {
        return new RedisKeys(prefix);
    }

    /**
     * 新建一个客户端（一条新连接），超时和应用的默认值一样。
     *
     * @return 模板
     */
    StringRedisTemplate client() {
        RedisStandaloneConfiguration server = RedisStateConfig.server(url);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                server,
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofSeconds(2))
                        .build());
        factory.afterPropertiesSet();
        factory.start();
        factories.add(factory);
        return new StringRedisTemplate(factory);
    }

    /**
     * 这个测试类写下的全部键。
     *
     * @return 键
     */
    Set<String> keysWritten() {
        StringRedisTemplate redis = client();
        Set<String> found = new TreeSet<>();
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(prefix + "*").count(500).build())) {
            cursor.forEachRemaining(found::add);
        }
        return found;
    }

    /** 删掉这个测试类写下的键，关掉所有连接。 */
    @Override
    public void close() {
        Set<String> written = keysWritten();
        if (!written.isEmpty()) {
            client().delete(written);
        }
        factories.forEach(LettuceConnectionFactory::destroy);
        factories.clear();
    }
}
