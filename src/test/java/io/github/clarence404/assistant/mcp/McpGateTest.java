package io.github.clarence404.assistant.mcp;

import static io.github.clarence404.assistant.mcp.McpFixtures.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.memory.ConversationOwner;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 第 8 章（ch08-2）：MCP 工具调用的「门」——是谁、要不要登录、有没有超次数。
 *
 * <p>身份的确认方式和第 6 章一样问商城 {@code /api/users/me}（假商城：{@link StubMallApi#TOKEN} 是用户 10001），不自己解析令牌。
 */
class McpGateTest {

    private static final StubMallApi MALL = StubMallApi.start();

    private McpGate gate;

    @AfterAll
    static void stop() {
        MALL.close();
    }

    @BeforeEach
    void setUp() {
        MALL.reset();
        gate = McpFixtures.mcp(MALL).gate();
    }

    @Test
    void anonymousCanUsePublicToolsWithoutCallingMall() {
        McpCaller caller = gate.enter(as(null), "searchKnowledge", false);

        assertThat(caller.owner()).isEqualTo(ConversationOwner.ANONYMOUS);
        assertThat(caller.token()).isNull();
        assertThat(MALL.requests()).isEmpty();
    }

    @Test
    void anonymousIsStoppedAtLoginRequiredTools() {
        assertThatThrownBy(() -> gate.enter(as(null), "queryOrder", true))
                .isInstanceOf(McpRejection.class)
                .hasMessage(McpGate.LOGIN_REQUIRED);
        assertThat(MALL.requests()).isEmpty();
    }

    @Test
    void tokenIsVerifiedByMall() {
        McpCaller caller = gate.enter(as(StubMallApi.TOKEN), "queryOrder", true);

        assertThat(caller.owner()).isEqualTo(ConversationOwner.user("10001"));
        assertThat(caller.token()).isEqualTo(StubMallApi.TOKEN);
        assertThat(caller.loggedIn()).isTrue();
        assertThat(MALL.requests()).extracting(StubMallApi.Request::path).containsExactly("/api/users/me");
    }

    @Test
    void ownerIsCachedLikeChapter6() {
        gate.enter(as(StubMallApi.TOKEN), "queryOrder", true);
        gate.enter(as(StubMallApi.TOKEN), "listMyOrders", true);

        assertThat(MALL.requests()).hasSize(1);
    }

    @Test
    void invalidTokenIsRejectedEvenForPublicTools() {
        assertThatThrownBy(() -> gate.enter(as("forged-token"), "searchKnowledge", false))
                .isInstanceOf(McpRejection.class)
                .hasMessage(McpGate.LOGIN_EXPIRED)
                .hasMessageNotContaining("forged-token");
    }

    @Test
    void mallDownMeansUnverifiedNotAnonymous() {
        MALL.failWith(503);

        assertThatThrownBy(() -> gate.enter(as(StubMallApi.TOKEN), "searchKnowledge", false))
                .isInstanceOf(McpRejection.class)
                .hasMessage(McpGate.OWNER_UNVERIFIED);
    }

    @Test
    void tooManyCallsAreRejectedPerCaller() {
        McpProperties two = new McpProperties(
                "http://localhost:8088/", 2000, 2, List.of(), Duration.ofMinutes(10), McpProperties.Client.defaults());
        McpGate limited = McpFixtures.mcp(MALL, two).gate();

        limited.enter(as(StubMallApi.TOKEN), "queryOrder", true);
        limited.enter(as(StubMallApi.TOKEN), "queryOrder", true);

        assertThatThrownBy(() -> limited.enter(as(StubMallApi.TOKEN), "queryOrder", true))
                .isInstanceOf(McpRejection.class)
                .hasMessageContaining("太频繁")
                .hasMessageContaining("2 次");
        // 别的用户不受影响
        assertThat(limited.enter(as(StubMallApi.NEW_USER_TOKEN), "listMyOrders", true)
                        .owner())
                .isEqualTo(ConversationOwner.user("10002"));
    }

    @Test
    void anonymousCallersShareOneQuota() {
        McpProperties one = new McpProperties(
                "http://localhost:8088/", 2000, 1, List.of(), Duration.ofMinutes(10), McpProperties.Client.defaults());
        McpGate limited = McpFixtures.mcp(MALL, one).gate();

        limited.enter(as(null), "searchKnowledge", false);

        assertThatThrownBy(() -> limited.enter(as(null), "queryProduct", false))
                .isInstanceOf(McpRejection.class)
                .hasMessageContaining("太频繁");
    }
}
