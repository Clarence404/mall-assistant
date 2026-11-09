package io.github.clarence404.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.chat.ChatClientConfig;
import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.mall.StubMallApi;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

/**
 * 第 6 章：对话记忆的打开、写回、查看、清空、过期、并发与摘要记忆（{@link ConversationMemory}）。
 *
 * <p>手法：内存存储 + 可以拨动的时钟（{@link MemoryFixtures}），归属由假商城确认。依赖 ch06-1（裁剪）、ch06-5（归属与记忆键）。
 */
class ConversationMemoryTest {

    private static final StubMallApi MALL = StubMallApi.start();
    private static final String ID = MemoryFixtures.CONVERSATION;

    private FakeChatModel model;
    private MemoryFixtures.Memory memory;

    @BeforeEach
    void setUp() {
        MALL.reset();
        model = new FakeChatModel();
        memory = memory(MemoryFixtures.properties());
    }

    @AfterAll
    static void stopMall() {
        MALL.close();
    }

    private MemoryFixtures.Memory memory(MemoryProperties properties) {
        return MemoryFixtures.memory(new ChatClientConfig().chatClient(ChatClient.builder(model)), MALL, properties);
    }

    /** 打开、写一轮、关掉。 */
    private void turn(MemoryFixtures.Memory m, String token, String user, String assistant) {
        try (Conversation conversation = m.memory().open(ID, token)) {
            conversation.save(user, assistant);
        }
    }

    /** 没带会话 ID：单轮，不读不写，调试信息为 null。 */
    @Test
    void noConversationIdIsSingleTurn() {
        try (Conversation conversation = memory.memory().open(null, null)) {
            assertThat(conversation.present()).isFalse();
            assertThat(conversation.active()).isFalse();
            assertThat(conversation.history()).isEmpty();
            assertThat(conversation.trace()).isNull();
            conversation.save("你好", "您好");
        }
        assertThat(memory.repository().findConversationIds()).isEmpty();
    }

    /** 写回的只有用户原话和助手的回答（引用编号去掉），带时间戳；下一轮打开时读得到。 */
    @Test
    void savedTurnIsReadBackNextTime() {
        turn(memory, null, "七天无理由退货要什么条件", "签收后 7 天内可以申请[1]，商品要完好[2]。");

        List<Message> stored = memory.stored(ConversationOwner.ANONYMOUS, ID);
        assertThat(stored).extracting(Message::getMessageType).containsExactly(MessageType.USER, MessageType.ASSISTANT);
        assertThat(stored).extracting(MemoryMessages::text).containsExactly("七天无理由退货要什么条件", "签收后 7 天内可以申请，商品要完好。");
        assertThat(stored)
                .allSatisfy(m -> assertThat(MemoryMessages.timestamp(m)).isEqualTo(MemoryFixtures.NOW));

        try (Conversation next = memory.memory().open(ID, null)) {
            assertThat(next.active()).isTrue();
            assertThat(next.history())
                    .extracting(MemoryMessages::text)
                    .containsExactly("七天无理由退货要什么条件", "签收后 7 天内可以申请，商品要完好。");
            MemoryTrace trace = next.trace();
            assertThat(trace.conversationId()).isEqualTo(ID);
            assertThat(trace.store()).isEqualTo("memory");
            assertThat(trace.historyTurns()).isEqualTo(1);
            assertThat(trace.droppedTurns()).isZero();
            assertThat(trace.expired()).isFalse();
        }
    }

    /** 提示词窗口：存了 8 轮，只带最近 6 轮，调试信息里记下裁掉了 2 轮；存储里还是 8 轮。 */
    @Test
    void historyIsTrimmedForPromptButKeptInStore() {
        for (int i = 1; i <= 8; i++) {
            turn(memory, null, "问题 " + i, "回答 " + i);
        }

        try (Conversation conversation = memory.memory().open(ID, null)) {
            assertThat(conversation.trace().historyTurns()).isEqualTo(6);
            assertThat(conversation.trace().droppedTurns()).isEqualTo(2);
            assertThat(conversation.history().getFirst().getText()).isEqualTo("问题 3");
        }
        assertThat(memory.stored(ConversationOwner.ANONYMOUS, ID)).hasSize(16);
    }

    /** 存储窗口：每个会话最多 40 条（20 轮），超出删最早的（MessageWindowChatMemory）。 */
    @Test
    void storeKeepsAtMostMaxMessages() {
        for (int i = 1; i <= 25; i++) {
            turn(memory, null, "问题 " + i, "回答 " + i);
        }

        List<Message> stored = memory.stored(ConversationOwner.ANONYMOUS, ID);
        assertThat(stored).hasSize(40);
        assertThat(stored.getFirst().getText()).isEqualTo("问题 6");
    }

    /** 过期：空闲超过 30 分钟，再打开时当新会话，旧历史清掉，调试信息标出 expired。 */
    @Test
    void idleConversationExpires() {
        turn(memory, null, "七天无理由退货要什么条件", "签收后 7 天内。");
        memory.clock().advance(Duration.ofMinutes(31));

        try (Conversation conversation = memory.memory().open(ID, null)) {
            assertThat(conversation.history()).isEmpty();
            assertThat(conversation.trace().expired()).isTrue();
        }
        assertThat(memory.stored(ConversationOwner.ANONYMOUS, ID)).isEmpty();
    }

    /** 没过期：29 分钟后还接得上。 */
    @Test
    void activeConversationDoesNotExpire() {
        turn(memory, null, "七天无理由退货要什么条件", "签收后 7 天内。");
        memory.clock().advance(Duration.ofMinutes(29));

        try (Conversation conversation = memory.memory().open(ID, null)) {
            assertThat(conversation.history()).hasSize(2);
            assertThat(conversation.trace().expired()).isFalse();
        }
    }

    /** 后台清理：过期的会话删掉，没过期的留着。 */
    @Test
    void sweepRemovesExpiredConversations() {
        turn(memory, null, "问题", "回答");
        memory.clock().advance(Duration.ofMinutes(20));
        try (Conversation other = memory.memory().open(MemoryFixtures.OTHER_CONVERSATION, null)) {
            other.save("另一个会话", "回答");
        }
        memory.clock().advance(Duration.ofMinutes(15));

        assertThat(memory.memory().sweep()).isEqualTo(1);
        assertThat(memory.stored(ConversationOwner.ANONYMOUS, ID)).isEmpty();
        assertThat(memory.stored(ConversationOwner.ANONYMOUS, MemoryFixtures.OTHER_CONVERSATION))
                .hasSize(2);
    }

    /** 同一个会话同时只能有一个请求：第二个直接 409；第一个关掉之后就可以了。 */
    @Test
    void concurrentRequestOnSameConversationIsBusy() {
        Conversation first = memory.memory().open(ID, null);

        assertThatThrownBy(() -> memory.memory().open(ID, null))
                .isInstanceOfSatisfying(
                        ConversationException.class,
                        e -> assertThat(e.failure()).isEqualTo(ConversationException.Failure.CONVERSATION_BUSY));
        try (Conversation other = memory.memory().open(MemoryFixtures.OTHER_CONVERSATION, null)) {
            assertThat(other.active()).as("别的会话不受影响").isTrue();
        }

        first.close();
        first.close();
        try (Conversation again = memory.memory().open(ID, null)) {
            assertThat(again.active()).isTrue();
        }
    }

    /** 隔离：用户 10001 的会话，10002 拿着同一个 ID 读不到、写进去的也是他自己的；匿名也一样。 */
    @Test
    void conversationsAreIsolatedByOwner() {
        turn(memory, StubMallApi.TOKEN, "订单 " + StubMallApi.ORDER_NO + " 到哪了", "待发货。");

        try (Conversation stranger = memory.memory().open(ID, StubMallApi.NEW_USER_TOKEN)) {
            assertThat(stranger.history()).isEmpty();
            stranger.save("我是别人", "好的");
        }
        try (Conversation anonymous = memory.memory().open(ID, null)) {
            assertThat(anonymous.history()).isEmpty();
        }

        assertThat(memory.stored(ConversationOwner.user("10001"), ID))
                .extracting(MemoryMessages::text)
                .containsExactly("订单 " + StubMallApi.ORDER_NO + " 到哪了", "待发货。");
        assertThat(memory.memory().messages(ID, StubMallApi.TOKEN)).hasSize(2);
        assertThat(memory.memory().messages(ID, StubMallApi.NEW_USER_TOKEN))
                .extracting(ConversationMessage::text)
                .containsExactly("我是别人", "好的");
        assertThatThrownBy(() -> memory.memory().messages(ID, null))
                .isInstanceOfSatisfying(
                        ConversationException.class,
                        e -> assertThat(e.failure()).isEqualTo(ConversationException.Failure.CONVERSATION_NOT_FOUND));
    }

    /** 登录失效、商城不可用：这一轮降级成单轮（不带历史、不写回），原因写进调试信息；查看接口则报错。 */
    @Test
    void unverifiedOwnerDegradesChatButFailsQueries() {
        try (Conversation conversation = memory.memory().open(ID, "forged-token")) {
            assertThat(conversation.present()).isTrue();
            assertThat(conversation.active()).isFalse();
            assertThat(conversation.trace().warning()).contains("登录已失效");
            conversation.save("不该被记下", "不该被记下");
        }
        assertThat(memory.repository().findConversationIds()).isEmpty();

        MALL.failWith(503);
        try (Conversation conversation = memory.memory().open(ID, StubMallApi.TOKEN)) {
            assertThat(conversation.active()).isFalse();
            assertThat(conversation.trace().warning()).contains("商城服务暂时不可用");
        }
        assertThatThrownBy(() -> memory.memory().messages(ID, StubMallApi.TOKEN))
                .isInstanceOfSatisfying(
                        ConversationException.class,
                        e -> assertThat(e.failure()).isEqualTo(ConversationException.Failure.OWNER_UNVERIFIED));
    }

    /** 查看：按时间顺序给出角色、文字、时间；没有的会话 404。 */
    @Test
    void messagesListsConversation() {
        turn(memory, null, "积分多久过期", "12 个月[1]。");

        assertThat(memory.memory().messages(ID, null))
                .containsExactly(
                        new ConversationMessage("user", "积分多久过期", MemoryFixtures.NOW),
                        new ConversationMessage("assistant", "12 个月。", MemoryFixtures.NOW));
        assertThatThrownBy(() -> memory.memory().messages(MemoryFixtures.OTHER_CONVERSATION, null))
                .isInstanceOf(ConversationException.class);
    }

    /** 清空：删掉之后再查是 404，再清也是 404；正在回答时不能清（409）。 */
    @Test
    void clearRemovesConversation() {
        turn(memory, null, "积分多久过期", "12 个月。");

        Conversation busy = memory.memory().open(ID, null);
        assertThatThrownBy(() -> memory.memory().clear(ID, null))
                .isInstanceOfSatisfying(
                        ConversationException.class,
                        e -> assertThat(e.failure()).isEqualTo(ConversationException.Failure.CONVERSATION_BUSY));
        busy.close();

        memory.memory().clear(ID, null);
        assertThat(memory.stored(ConversationOwner.ANONYMOUS, ID)).isEmpty();
        assertThatThrownBy(() -> memory.memory().clear(ID, null))
                .isInstanceOfSatisfying(
                        ConversationException.class,
                        e -> assertThat(e.failure()).isEqualTo(ConversationException.Failure.CONVERSATION_NOT_FOUND));
    }

    /** 记忆库挂了：打开时降级成单轮；查看 503。 */
    @Test
    void brokenStoreDegrades() {
        MemoryProperties properties = MemoryFixtures.properties();
        org.springframework.ai.chat.memory.ChatMemory broken = new org.springframework.ai.chat.memory.ChatMemory() {
            @Override
            public void add(String conversationId, List<Message> messages) {
                throw new IllegalStateException("数据库连不上");
            }

            @Override
            public List<Message> get(String conversationId) {
                throw new IllegalStateException("数据库连不上");
            }

            @Override
            public void clear(String conversationId) {
                throw new IllegalStateException("数据库连不上");
            }
        };
        ConversationMemory down = new ConversationMemory(
                broken,
                memory.repository(),
                memory.owners(),
                new ConversationSummarizer(new ChatClientConfig().chatClient(ChatClient.builder(model)), properties),
                properties,
                memory.clock());

        try (Conversation conversation = down.open(ID, null)) {
            assertThat(conversation.active()).isFalse();
            assertThat(conversation.trace().warning()).contains("对话记录暂时读写不了");
        }
        try (Conversation again = down.open(ID, null)) {
            assertThat(again.present()).as("降级时也放开了占用").isTrue();
        }
        assertThatThrownBy(() -> down.messages(ID, null))
                .isInstanceOfSatisfying(
                        ConversationException.class,
                        e -> assertThat(e.failure()).isEqualTo(ConversationException.Failure.MEMORY_UNAVAILABLE));
    }

    /** 摘要记忆（默认关）：超过 2 轮的早期对话压成一条摘要，原话删掉；下一轮的历史里摘要以带标签的用户消息出现在最前面。 */
    @Test
    void summaryCompactsOverflowTurns() {
        MemoryFixtures.Memory summarizing = memory(MemoryFixtures.properties(2, 2000, true));
        model.willSummarize("用户问了订单 " + StubMallApi.ORDER_NO + " 的物流，客服答待发货。");

        turn(summarizing, null, "订单 " + StubMallApi.ORDER_NO + " 到哪了", "待发货。");
        turn(summarizing, null, "什么时候发货", "48 小时内。");
        assertThat(model.summaryPrompts()).as("还没超过窗口，不压缩").isEmpty();
        turn(summarizing, null, "积分多久过期", "12 个月。");

        assertThat(model.summaryPrompts()).hasSize(1);
        assertThat(model.summaryPrompts().getFirst().getUserMessage().getText())
                .contains("<要压缩的对话>")
                .contains("用户：订单 " + StubMallApi.ORDER_NO + " 到哪了")
                .doesNotContain("积分多久过期");
        List<Message> stored = summarizing.stored(ConversationOwner.ANONYMOUS, ID);
        assertThat(stored)
                .extracting(Message::getMessageType)
                .containsExactly(
                        MessageType.SYSTEM,
                        MessageType.USER,
                        MessageType.ASSISTANT,
                        MessageType.USER,
                        MessageType.ASSISTANT);

        try (Conversation next = summarizing.memory().open(ID, null)) {
            assertThat(next.trace().summarized()).isTrue();
            Message first = next.history().getFirst();
            assertThat(first.getMessageType()).as("摘要不进系统消息").isEqualTo(MessageType.USER);
            assertThat(first.getText()).contains("<早期对话摘要>").contains("待发货");
        }
    }

    /** 摘要记忆关着（默认）：不调模型。 */
    @Test
    void summaryIsOffByDefault() {
        for (int i = 1; i <= 8; i++) {
            turn(memory, null, "问题 " + i, "回答 " + i);
        }
        assertThat(model.prompts()).isEmpty();
    }
}
