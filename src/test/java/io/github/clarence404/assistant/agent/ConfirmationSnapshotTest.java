package io.github.clarence404.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.aftersales.InMemoryAfterSalesStore;
import io.github.clarence404.assistant.chat.ChatClientConfig;
import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallApiProperties;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.memory.ConversationOwner;
import io.github.clarence404.assistant.memory.MemoryFixtures;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 第 8 章（ch08-7）：MCP 来的待确认操作——登记、给页面看卡片、给 MCP 工具查状态，执行仍然只走第 7 章的 {@code decide}。
 *
 * <ul>
 *   <li>状态：PENDING → EXECUTED / REJECTED / FAILED，没点就 EXPIRED
 *   <li>只给本人看：别人、匿名都是 404；聊天里生成的确认（绑定会话）不能借这个口子看
 *   <li>聊天里说「确认」时的提醒不把 MCP 的操作混进来
 * </ul>
 */
class ConfirmationSnapshotTest {

    private static final StubMallApi MALL = StubMallApi.start();
    private static final ConversationOwner DEMO = ConversationOwner.user("10001");
    private static final String PENDING = StubMallApi.PENDING_PAYMENT_ORDER_NO;

    private ConfirmationService confirmations;
    private MemoryFixtures.MutableClock clock;

    @AfterAll
    static void stop() {
        MALL.close();
    }

    @BeforeEach
    void setUp() {
        MALL.reset();
        ChatClient chatClient = new ChatClientConfig().chatClient(ChatClient.builder(new FakeChatModel()));
        MemoryFixtures.Memory memory = MemoryFixtures.memory(chatClient, MALL, MemoryFixtures.properties());
        MallApiClient mallApi =
                new MallApiClient(new MallApiProperties(MALL.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2)));
        clock = memory.clock();
        confirmations = new ConfirmationService(
                mallApi,
                new InMemoryAfterSalesStore(),
                memory.memory(),
                memory.owners(),
                AgentProperties.defaults(AgentProperties.Mode.LOOP),
                clock);
    }

    private static PendingAction cancel() {
        return new PendingAction(AgentTool.CANCEL_ORDER, PENDING, Map.of(), "取消订单 " + PENDING, "取消后订单关闭、不能恢复。");
    }

    @Test
    void pendingThenExecuted() {
        String id = confirmations.createForMcp(cancel(), DEMO).id();

        ConfirmationSnapshot before = confirmations.snapshot(id, DEMO);
        confirmations.decide(id, ConfirmationOutcome.Decision.APPROVE, null, StubMallApi.TOKEN);
        ConfirmationSnapshot after = confirmations.snapshot(id, DEMO);

        assertThat(before.state()).isEqualTo(ConfirmationSnapshot.State.PENDING);
        assertThat(before.message()).isEqualTo(ConfirmationService.PENDING_MESSAGE);
        assertThat(before.confirmation().summary()).isEqualTo("取消订单 " + PENDING);
        assertThat(after.state()).isEqualTo(ConfirmationSnapshot.State.EXECUTED);
        assertThat(after.message()).startsWith("已取消订单 " + PENDING);
        assertThat(MALL.orderStatus(PENDING)).isEqualTo("CANCELLED");
    }

    @Test
    void rejectedAndFailed() {
        String rejected = confirmations.createForMcp(cancel(), DEMO).id();
        confirmations.decide(rejected, ConfirmationOutcome.Decision.REJECT, null, StubMallApi.TOKEN);
        String failed = confirmations.createForMcp(cancel(), DEMO).id();
        MALL.failWith(503);
        confirmations.decide(failed, ConfirmationOutcome.Decision.APPROVE, null, StubMallApi.TOKEN);

        assertThat(confirmations.snapshot(rejected, DEMO).state()).isEqualTo(ConfirmationSnapshot.State.REJECTED);
        assertThat(confirmations.snapshot(failed, DEMO).state()).isEqualTo(ConfirmationSnapshot.State.FAILED);
    }

    @Test
    void expiredWithoutClick() {
        String id = confirmations.createForMcp(cancel(), DEMO).id();
        clock.advance(Duration.ofMinutes(5));

        ConfirmationSnapshot snapshot = confirmations.snapshot(id, DEMO);

        assertThat(snapshot.state()).isEqualTo(ConfirmationSnapshot.State.EXPIRED);
        assertThat(snapshot.message()).contains("过期", "没有执行");
    }

    @Test
    void onlyTheOwnerCanSeeIt() {
        String id = confirmations.createForMcp(cancel(), DEMO).id();

        assertThatThrownBy(() -> confirmations.snapshot(id, ConversationOwner.user("10002")))
                .isInstanceOf(ConfirmationException.class)
                .extracting(e -> ((ConfirmationException) e).failure())
                .isEqualTo(ConfirmationException.Failure.CONFIRMATION_NOT_FOUND);
        assertThatThrownBy(() -> confirmations.view(id, null)).isInstanceOf(ConfirmationException.class);
        assertThatThrownBy(() -> confirmations.view(id, StubMallApi.NEW_USER_TOKEN))
                .isInstanceOf(ConfirmationException.class);
        assertThat(confirmations.view(id, StubMallApi.TOKEN).state()).isEqualTo(ConfirmationSnapshot.State.PENDING);
        assertThatThrownBy(() -> confirmations.snapshot("00000000-0000-0000-0000-000000000000", DEMO))
                .isInstanceOf(ConfirmationException.class);
    }

    @Test
    void chatConfirmationsAreNotVisibleThroughTheMcpDoor() {
        String id = confirmations
                .create(cancel(), DEMO, MemoryFixtures.CONVERSATION)
                .id();

        assertThatThrownBy(() -> confirmations.snapshot(id, DEMO))
                .isInstanceOf(ConfirmationException.class)
                .extracting(e -> ((ConfirmationException) e).failure())
                .isEqualTo(ConfirmationException.Failure.CONFIRMATION_NOT_FOUND);
    }

    @Test
    void mcpConfirmationHasNoConversation() {
        String id = confirmations.createForMcp(cancel(), DEMO).id();

        // 带着某个会话 ID 来点：对不上（MCP 的确认不属于任何会话），和第 7 章一样 404
        assertThatThrownBy(() -> confirmations.decide(
                        id, ConfirmationOutcome.Decision.APPROVE, MemoryFixtures.CONVERSATION, StubMallApi.TOKEN))
                .isInstanceOf(ConfirmationException.class);
        assertThat(MALL.writes()).isEmpty();
        // 聊天里说「确认」的提醒只看聊天来的确认（单轮对话的会话 ID 也是 null）
        assertThat(confirmations.pendingFor(DEMO, null)).isEmpty();
    }
}
