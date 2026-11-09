package io.github.clarence404.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.mall.StubMallApi;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 第 6 章 TODO ch06-5：会话归属——没令牌是匿名；有令牌就问商城「你是谁」（结果缓存）；令牌无效 / 商城不可用分别报错；记忆键由归属和会话 ID
 * 一起算出，换了人就是另一个键。
 *
 * <p>手法：{@link StubMallApi} 的 {@code /api/users/me} 认两个令牌（10001、10002），其余 401；它记下每个请求，断言「有没有问商城」。
 */
class ConversationOwnersTest {

    private static final StubMallApi MALL = StubMallApi.start();

    private MemoryFixtures.MutableClock clock;
    private ConversationOwners owners;

    @BeforeEach
    void setUp() {
        MALL.reset();
        clock = new MemoryFixtures.MutableClock(MemoryFixtures.NOW);
        owners = new ConversationOwners(MemoryFixtures.mallApi(MALL), MemoryFixtures.properties(), clock);
    }

    @AfterAll
    static void stopMall() {
        MALL.close();
    }

    private long meCalls() {
        return MALL.requests().stream()
                .filter(r -> r.path().equals("/api/users/me"))
                .count();
    }

    /** 没登录：匿名，不问商城。 */
    @Test
    void noTokenIsAnonymous() {
        assertThat(owners.resolve(null)).isEqualTo(ConversationOwner.ANONYMOUS);
        assertThat(MALL.requests()).isEmpty();
    }

    /** 登录了：商城说这个令牌是 10001，带着令牌去问的。 */
    @Test
    void tokenIsResolvedByMall() {
        ConversationOwner owner = owners.resolve(StubMallApi.TOKEN);

        assertThat(owner).isEqualTo(ConversationOwner.user("10001"));
        assertThat(owner.key()).isEqualTo("user:10001");
        assertThat(MALL.requests()).singleElement().satisfies(r -> {
            assertThat(r.path()).isEqualTo("/api/users/me");
            assertThat(r.authorization()).isEqualTo("Bearer " + StubMallApi.TOKEN);
        });
        assertThat(owners.resolve(StubMallApi.NEW_USER_TOKEN)).isEqualTo(ConversationOwner.user("10002"));
    }

    /** 缓存：同一个令牌在 owner-cache-ttl（5 分钟）内只问一次商城，过期后再问。 */
    @Test
    void resultIsCachedUntilTtl() {
        owners.resolve(StubMallApi.TOKEN);
        owners.resolve(StubMallApi.TOKEN);
        clock.advance(Duration.ofMinutes(4));
        owners.resolve(StubMallApi.TOKEN);
        assertThat(meCalls()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(2));
        owners.resolve(StubMallApi.TOKEN);
        assertThat(meCalls()).isEqualTo(2);
    }

    /** 令牌无效：LOGIN_EXPIRED（401），不缓存失败。 */
    @Test
    void invalidTokenIsLoginExpired() {
        assertThatThrownBy(() -> owners.resolve("forged-token"))
                .isInstanceOfSatisfying(
                        ConversationException.class,
                        e -> assertThat(e.failure()).isEqualTo(ConversationException.Failure.LOGIN_EXPIRED));
        assertThatThrownBy(() -> owners.resolve("forged-token")).isInstanceOf(ConversationException.class);
        assertThat(meCalls()).isEqualTo(2);
    }

    /** 商城挂了：OWNER_UNVERIFIED（503），确认不了是谁，不能瞎猜。 */
    @Test
    void mallDownIsOwnerUnverified() {
        MALL.failWith(503);

        assertThatThrownBy(() -> owners.resolve(StubMallApi.TOKEN))
                .isInstanceOfSatisfying(
                        ConversationException.class,
                        e -> assertThat(e.failure()).isEqualTo(ConversationException.Failure.OWNER_UNVERIFIED));
    }

    /** 记忆键：同一个人同一个会话 ID 每次一样；换了人（别的用户、匿名）就是另一个键；格式是 36 位 UUID，放得进 VARCHAR(36)。 */
    @Test
    void memoryKeyDependsOnOwnerAndConversation() {
        String mine = owners.memoryKey(ConversationOwner.user("10001"), MemoryFixtures.CONVERSATION);

        assertThat(owners.memoryKey(ConversationOwner.user("10001"), MemoryFixtures.CONVERSATION))
                .isEqualTo(mine);
        assertThat(owners.memoryKey(ConversationOwner.user("10002"), MemoryFixtures.CONVERSATION))
                .isNotEqualTo(mine);
        assertThat(owners.memoryKey(ConversationOwner.ANONYMOUS, MemoryFixtures.CONVERSATION))
                .isNotEqualTo(mine);
        assertThat(owners.memoryKey(ConversationOwner.user("10001"), MemoryFixtures.OTHER_CONVERSATION))
                .isNotEqualTo(mine);
        assertThat(mine).hasSize(36).matches(ConversationIds.PATTERN).isNotEqualTo(MemoryFixtures.CONVERSATION);
    }

    /** 缓存里存的是令牌的指纹，不是令牌原文。 */
    @Test
    void fingerprintDoesNotContainToken() {
        String fingerprint = ConversationOwners.fingerprint(StubMallApi.TOKEN);

        assertThat(fingerprint).hasSize(64).doesNotContain("stub-mall-token");
        assertThat(ConversationOwners.fingerprint(StubMallApi.TOKEN)).isEqualTo(fingerprint);
    }
}
