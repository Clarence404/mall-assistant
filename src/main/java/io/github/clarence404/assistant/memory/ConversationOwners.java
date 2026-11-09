package io.github.clarence404.assistant.memory;

import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallFailure;
import io.github.clarence404.assistant.mall.MallModels;
import io.github.clarence404.assistant.mall.MallResult;
import io.github.clarence404.assistant.mall.MallToken;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 会话归属：这个请求是谁发的（匿名 / 哪个商城用户），以及他的会话在记忆库里用哪个键（第 6 章引入）。
 *
 * <p>所在层：业务层。上游是 {@link ConversationMemory}；下游是 {@link MallApiClient#me}（问商城「这个令牌是谁」）。
 *
 * <p><b>为什么要管归属</b>：会话 ID 是页面生成、存在浏览器里、每次请求都带着的，它可能被别人看到（截图、日志、共用电脑）。如果记忆只按会话 ID
 * 存取，拿到 ID 的人就能读到别人的对话（里面有订单号、收货信息），还能往里面塞话（下一轮模型会把这些话当成用户说过的）。所以记忆要绑定到
 * 「是谁的」，别人拿着同一个 ID 也读不到、写不进。
 *
 * <p><b>谁是谁</b>：没带商城令牌的是匿名访客；带了令牌的，问商城 {@code GET /api/users/me} 拿到用户 ID。不自己解析令牌：商城令牌是 JWT，
 * 解开中间一段就能看到用户 ID，但我们没有商城的密钥、验不了签名，伪造一个写着别人 ID 的令牌轻而易举；让商城验，拿到的 ID 才可信。结果按令牌的
 * 指纹（SHA-256）缓存 {@code owner-cache-ttl}，不用每句话都问一次商城；缓存里不存令牌原文。
 *
 * <p>另一种做法是「令牌指纹」本身当归属（不问商城）：简单、不依赖商城，但商城令牌 30 分钟就过期，重新登录换了令牌，之前的会话就再也打不开了。
 * 商城有现成的「我是谁」接口，所以用它。
 *
 * <p><b>怎么隔离</b>：记忆库里的键不是会话 ID 本身，而是由「归属 + 会话 ID」算出来的另一个 UUID（{@link #memoryKey}）。别人拿着你的会话 ID 来，
 * 算出的是<b>他自己的</b>键，读到的是空会话、写进的也是他自己的会话，碰不到你的。不用另建一张「会话归属表」，也不会因为忘了某处校验而漏掉。
 */
@Component
public class ConversationOwners {

    private static final Logger log = LoggerFactory.getLogger(ConversationOwners.class);

    private final MallApiClient mallApi;
    private final MemoryProperties properties;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * 创建。
     *
     * @param mallApi 商城客户端（查当前用户）
     * @param properties {@code assistant.memory.*}（缓存时长）
     * @param clock 时钟（测试里换成可以拨动的）
     */
    public ConversationOwners(MallApiClient mallApi, MemoryProperties properties, Clock clock) {
        this.mallApi = mallApi;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 这个请求的会话归谁。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>令牌为 null：{@link ConversationOwner#ANONYMOUS}，不调商城
     *   <li>缓存里有这个令牌（按 {@link #fingerprint} 查）且没过期：直接返回缓存的归属，不调商城
     *   <li>否则调 {@code mallApi.me(令牌)}：成功 → {@link ConversationOwner#user}（用户 ID 取自响应），放进缓存，{@code owner-cache-ttl} 后过期；
     *       {@link MallFailure#UNAUTHORIZED} → 抛 {@link ConversationException.Failure#LOGIN_EXPIRED}；其他失败 → 抛
     *       {@link ConversationException.Failure#OWNER_UNVERIFIED}。失败都不缓存
     * </ul>
     *
     * @param mallToken 商城令牌，可以为 null
     * @return 归属
     * @throws ConversationException 令牌无效（LOGIN_EXPIRED）或商城不可用（OWNER_UNVERIFIED）
     */
    public ConversationOwner resolve(String mallToken) {
        if (mallToken == null) {
            return ConversationOwner.ANONYMOUS;
        }
        String fingerprint = fingerprint(mallToken);
        Instant now = clock.instant();
        Cached cached = cache.get(fingerprint);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.owner();
        }
        MallResult<MallModels.UserProfile> me = mallApi.me(mallToken);
        if (me.isOk() && me.body().userId() != null) {
            ConversationOwner owner = ConversationOwner.user(me.body().userId());
            cache.put(fingerprint, new Cached(owner, now.plus(properties.ownerCacheTtl())));
            return owner;
        }
        log.info("确认会话归属失败（令牌 {}）：{}", MallToken.mask(mallToken), me.failure());
        if (me.failure() == MallFailure.UNAUTHORIZED) {
            throw new ConversationException(ConversationException.Failure.LOGIN_EXPIRED, null);
        }
        throw new ConversationException(ConversationException.Failure.OWNER_UNVERIFIED, null);
    }

    /**
     * 记忆库里的键：由「归属 + 会话 ID」算出的 UUID（{@link UUID#nameUUIDFromBytes}，同样的输入永远得到同样的结果）。
     *
     * <p>要求（测试按这个写）：输入是 {@code owner.key() + "/" + conversationId} 的 UTF-8 字节；同一个人同一个会话 ID 每次得到同一个键，
     * 换了归属（别的用户、匿名）就是另一个键。
     *
     * <p>为什么还是 UUID：Spring AI 的 JDBC 记忆表 {@code conversation_id} 列是 {@code VARCHAR(36)}，正好放得下一个 UUID；直接拼成
     * 「user:10001/会话 ID」放不下。代价是在数据库里看到的键和页面上的会话 ID 对不上，排查时要用同样的算法算一遍。
     *
     * @param owner 归属
     * @param conversationId 页面传来的会话 ID（已校验过格式）
     * @return 记忆库里的键，36 个字符
     */
    public String memoryKey(ConversationOwner owner, String conversationId) {
        return UUID.nameUUIDFromBytes((owner.key() + "/" + conversationId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    /** 清掉过期的缓存项（由 {@link ConversationMemory#sweep} 定期调用）。 */
    void evictExpired() {
        Instant now = clock.instant();
        cache.values().removeIf(c -> !c.expiresAt().isAfter(now));
    }

    /**
     * 令牌的指纹：SHA-256 的十六进制。缓存用它当键，内存里不留令牌原文。
     *
     * @param token 令牌
     * @return 64 个十六进制字符
     */
    static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 缓存项：归属与过期时间。 */
    private record Cached(ConversationOwner owner, Instant expiresAt) {}
}
