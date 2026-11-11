package io.github.clarence404.assistant.state;

import io.github.clarence404.assistant.agent.ConfirmationOutcome;
import io.github.clarence404.assistant.agent.ConfirmationStore;
import io.github.clarence404.assistant.agent.StoredConfirmation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 待确认操作的 Redis 实现：A 实例生成的确认卡片，B 实例也能确认，而且只执行一次（第 10 章引入）。
 *
 * <p>所在层：共享状态。{@code assistant.state.store=redis} 时注入 {@code agent.ConfirmationService}。每个确认三个键（都带花括号，见 {@link RedisKeys}）：
 *
 * <ul>
 *   <li>{@code confirm:{ID}}：确认本身（JSON），过期时间 + 1 小时后自动删
 *   <li>{@code confirm:{ID}:claim}：谁抢到了、决定是什么（{@code APPROVE} / {@code REJECT}），{@code SET NX PX} 写入
 *   <li>{@code confirm:{ID}:outcome}：结果（JSON）
 * </ul>
 *
 * 外加每个用户一个索引 {@code confirm-owner:用户}（有序集合，确认 ID → 过期时间），聊天里说「确认」时找他还没处理的那个。
 *
 * <p><b>出错一律拒绝</b>（{@link StateFailures#fail}，不听 {@code on-redis-error}）：存不进去就不生成卡片，读不到就不执行。
 * 写操作「出错就放行」的后果是执行两次，这是这一章唯一一个没得选的地方。
 *
 * <p><b>存 JSON</b>：Redis 只存字符串，确认里有记录、枚举、时间、Map，用 Jackson 转成 JSON（Jackson 3 自带 {@code java.time} 支持，
 * 时间写成 ISO 字符串）。好处是在 redis-cli 里 {@code GET} 一眼看得懂；代价是改了记录的字段，旧的 JSON 可能读不回来——
 * 确认最多活一个多小时，发版时旧的读不回来就当它不存在（404），可以接受。
 */
public final class RedisConfirmationStore implements ConfirmationStore {

    private static final String COMPONENT = "confirmation";

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final JsonMapper json;
    private final StateFailures failures;

    /**
     * 创建。
     *
     * @param redis Redis 客户端
     * @param keys 键前缀
     * @param json JSON 转换
     * @param failures 出错时怎么办（这里一律拒绝）
     */
    public RedisConfirmationStore(StringRedisTemplate redis, RedisKeys keys, JsonMapper json, StateFailures failures) {
        this.redis = redis;
        this.keys = keys;
        this.json = json;
        this.failures = failures;
    }

    @Override
    public void save(StoredConfirmation confirmation, Duration ttl) {
        try {
            failures.ensureAvailable();
            redis.opsForValue().set(key(confirmation.id()), json.writeValueAsString(confirmation), ttl);
            String index = ownerIndex(confirmation.ownerKey());
            redis.opsForZSet()
                    .add(index, confirmation.id(), confirmation.expiresAt().toEpochMilli());
            redis.expire(index, ttl);
        } catch (DataAccessException e) {
            throw failures.fail(COMPONENT, e);
        }
    }

    @Override
    public Optional<StoredConfirmation> find(String id) {
        try {
            failures.ensureAvailable();
            List<String> values = redis.opsForValue().multiGet(List.of(key(id), key(id) + ":outcome"));
            if (values == null || values.getFirst() == null) {
                return Optional.empty();
            }
            StoredConfirmation stored = read(values.getFirst(), StoredConfirmation.class);
            if (stored == null) {
                return Optional.empty();
            }
            String outcome = values.get(1);
            return Optional.of(outcome == null ? stored : stored.withOutcome(read(outcome, ConfirmationOutcome.class)));
        } catch (DataAccessException e) {
            throw failures.fail(COMPONENT, e);
        }
    }

    /**
     * 抢占（ch10-4 之一）。
     *
     * <p>要求（测试按这个写）：{@code SET confirm:{ID}:claim 决定 NX PX ttl}（{@code opsForValue().setIfAbsent(键, 值, ttl)}）。
     * 写进去了（true）→ 返回空，我抢到了；没写进去 → {@code GET} 出别人的决定返回（读出来是 null——别人的记录恰好过期了——也当作有人在处理，
     * 返回我自己的决定，调用方会去等结果，等不到是 409）。Redis 出错：{@code throw failures.fail(COMPONENT, e)}。
     *
     * <p>为什么一条 {@code SET NX} 就够、不用 Lua：「不存在才写」本身就是 Redis 的一个原子命令；后面那条 {@code GET} 只是读别人的决定，
     * 在两条命令之间别人的记录不会变（抢占记录写进去以后没人改它）。
     */
    @Override
    public Optional<ConfirmationOutcome.Decision> claim(
            String id, ConfirmationOutcome.Decision decision, Duration ttl) {
        // TODO ch10-4：failures.ensureAvailable()；抢占键 = key(id) + ":claim"；
        // opsForValue().setIfAbsent(抢占键, decision.name(), ttl) 为 TRUE → Optional.empty()（我抢到了）；
        // 否则 get(抢占键) 读出别人的决定：null 时返回我自己的 decision，否则 Decision.valueOf(...)。
        // catch (DataAccessException e)：throw failures.fail(COMPONENT, e)
        return Optional.empty();
    }

    /**
     * 记下结果（ch10-4 之二）。
     *
     * <p>要求（测试按这个写）：结果转成 JSON，{@code SET confirm:{ID}:outcome JSON PX ttl}；Redis 出错：{@code throw failures.fail(COMPONENT, e)}。
     */
    @Override
    public void complete(String id, ConfirmationOutcome outcome, Duration ttl) {
        // TODO ch10-4：failures.ensureAvailable()；opsForValue().set(key(id) + ":outcome",
        // json.writeValueAsString(outcome), ttl)。
        // catch (DataAccessException e)：throw failures.fail(COMPONENT, e)
    }

    @Override
    public List<StoredConfirmation> findByOwner(String ownerKey) {
        try {
            failures.ensureAvailable();
            Set<String> ids = redis.opsForZSet().range(ownerIndex(ownerKey), 0, -1);
            List<StoredConfirmation> result = new ArrayList<>();
            if (ids != null) {
                for (String id : ids) {
                    find(id).ifPresent(result::add);
                }
            }
            return result;
        } catch (DataAccessException e) {
            throw failures.fail(COMPONENT, e);
        }
    }

    /** JSON → 对象；读不回来（字段变了）当作不存在。 */
    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** 确认的键：{@code 前缀confirm:{ID}}（花括号让同一个确认的三个键在 Cluster 里落在同一个节点）。 */
    private String key(String id) {
        return keys.of("confirm", "{" + id + "}");
    }

    private String ownerIndex(String ownerKey) {
        return keys.of("confirm-owner", ownerKey);
    }
}
