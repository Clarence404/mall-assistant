package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.memory.ConversationOwner;
import io.github.clarence404.assistant.memory.MemoryFixtures;
import io.github.clarence404.assistant.memory.MemoryMessages;
import io.github.clarence404.assistant.memory.MemoryTrace;
import io.github.clarence404.assistant.tool.ToolInvocation;
import java.net.ConnectException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * 第 6 章 TODO ch06-2 / ch06-6（及 ch06-3、ch06-4 在对话里的效果）：多轮对话——历史放进提示词、追问先改写再检索、订单号沿用、只存原话、
 * 流式回答完才写记忆、失败与取消不写、会话隔离。
 *
 * <p>手法：同第 3、5 章的流程测试，不启动 Spring，手工组装带记忆的 ChatService（{@link MemoryFixtures#chat}）：模型是 {@link FakeChatModel}，
 * 知识库是假向量模型 + 6 篇真实规则文档，商城是 {@link StubMallApi}，记忆是内存存储。每个测试连说两三句，断言第二句发给模型的提示词和存进记忆的内容。
 */
class MultiTurnChatFlowTest {

    private static final StubMallApi MALL = StubMallApi.start();
    private static final String ID = MemoryFixtures.CONVERSATION;

    private static final String POLICY = "{\"intent\":\"POLICY\",\"confidence\":0.9,\"orderNo\":null,\"skuId\":null}";
    private static final String UNKNOWN = FakeChatModel.UNKNOWN_INTENT_JSON;
    private static final String ORDER_WITH_NO = "{\"intent\":\"ORDER_STATUS\",\"confidence\":0.9,\"orderNo\":\""
            + StubMallApi.ORDER_NO + "\",\"skuId\":null}";
    private static final String ORDER_WITHOUT_NO =
            "{\"intent\":\"ORDER_STATUS\",\"confidence\":0.9,\"orderNo\":null,\"skuId\":null}";
    private static final String OUT_OF_SCOPE =
            "{\"intent\":\"OUT_OF_SCOPE\",\"confidence\":0.95,\"orderNo\":null,\"skuId\":null}";

    private FakeChatModel model;
    private MemoryFixtures.Chat chat;

    @BeforeEach
    void setUp() {
        MALL.reset();
        model = new FakeChatModel();
        chat = MemoryFixtures.chat(model, MALL, MemoryFixtures.properties());
    }

    @AfterAll
    static void stopMall() {
        MALL.close();
    }

    private ChatService service() {
        return chat.service();
    }

    private List<Message> stored(ConversationOwner owner) {
        return chat.memory().stored(owner, ID);
    }

    /** 没带会话 ID：和第 5 章一样单轮，不读不写，memory 为 null。 */
    @Test
    void withoutConversationIdNothingIsRemembered() {
        model.willReply("您好");

        ChatReply first = service().chat("你好", null, null);
        service().chat("刚才我说了什么", null, null);

        assertThat(first.memory()).isNull();
        assertThat(model.answerPrompts().getLast().getInstructions()).hasSize(2);
        assertThat(chat.memory().repository().findConversationIds()).isEmpty();
    }

    /** 第二句的提示词：系统消息 → 第一轮的用户原话、助手回答 → 第二句；调试信息里带了 1 轮。 */
    @Test
    void historyGoesBetweenSystemAndUserMessage() {
        model.willClassify(UNKNOWN);
        model.willReply("您好，我是小示。");
        service().chat("你好，我叫小王", ID, null);

        model.willReply("您叫小王。");
        ChatReply second = service().chat("我叫什么", ID, null);

        Prompt prompt = model.answerPrompts().getLast();
        assertThat(prompt.getInstructions())
                .extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
        assertThat(prompt.getInstructions()).extracting(Message::getText).endsWith("你好，我叫小王", "您好，我是小示。", "我叫什么");
        MemoryTrace trace = second.memory();
        assertThat(trace.active()).isTrue();
        assertThat(trace.historyTurns()).isEqualTo(1);
        assertThat(trace.droppedTurns()).isZero();
        assertThat(stored(ConversationOwner.ANONYMOUS)).hasSize(4);
    }

    /** 意图识别不带历史：只看这一句（改写不出来时就是原话；第 2 章的录制照样能回放）。 */
    @Test
    void intentClassificationSeesOnlyTheCurrentMessage() {
        model.willReply("好的");
        service().chat("你好，我叫小王", ID, null);
        service().chat("我叫什么", ID, null);

        Prompt classification = model.classificationPrompts().getLast();
        assertThat(classification.getInstructions()).hasSize(2);
        assertThat(FakeChatModel.allText(classification)).doesNotContain("小王");
    }

    /** 存什么：直接查询的「查询结果」、商城令牌都不进记忆，只存用户原话和最终回答。 */
    @Test
    void onlyOriginalWordsAndFinalReplyAreStored() {
        model.willClassify(ORDER_WITH_NO);
        model.willReply("您的订单待发货。");

        ChatReply reply = service().chat("订单 " + StubMallApi.ORDER_NO + " 到哪了", ID, StubMallApi.TOKEN);

        assertThat(FakeChatModel.allText(model.answerPrompts().getLast())).contains("<查询结果>");
        List<Message> stored = stored(ConversationOwner.user("10001"));
        assertThat(stored)
                .extracting(MemoryMessages::text)
                .containsExactly("订单 " + StubMallApi.ORDER_NO + " 到哪了", reply.reply());
        String all = String.join("\n", stored.stream().map(MemoryMessages::text).toList());
        assertThat(all)
                .doesNotContain("查询结果")
                .doesNotContain("PENDING_SHIPMENT")
                .doesNotContain(StubMallApi.TOKEN);
    }

    /** RAG 的参考资料不进记忆，回答里的引用编号也去掉（编号只对那一轮的资料有效）。 */
    @Test
    void ragSourcesAreNotStored() {
        model.willClassify(POLICY);
        model.willReply("签收后 7 天内可以申请[1]。");

        service().chat("七天无理由退货要什么条件", ID, null);

        assertThat(FakeChatModel.allText(model.answerPrompts().getLast())).contains("<参考资料>");
        assertThat(stored(ConversationOwner.ANONYMOUS))
                .extracting(MemoryMessages::text)
                .containsExactly("七天无理由退货要什么条件", "签收后 7 天内可以申请。");
    }

    /**
     * 追问改写：第二句「那运费谁出」先改写成独立问题，检索、拼提示词都用改写后的；历史也一起交给模型；改写结果在调试信息里。
     */
    @Test
    void followUpIsCondensedBeforeRetrieval() {
        model.willClassify(POLICY);
        model.willReply("签收后 7 天内、商品完好就可以申请[1]。");
        service().chat("七天无理由退货要什么条件", ID, null);

        model.willCondense("七天无理由退货的运费谁出");
        model.willReply("非质量问题的退货运费由买家承担[1]。");
        ChatReply second = service().chat("那运费谁出", ID, null);

        assertThat(model.condensePrompts()).hasSize(1);
        assertThat(model.condensePrompts().getFirst().getUserMessage().getText())
                .contains("用户：七天无理由退货要什么条件")
                .contains("最新一句：那运费谁出");
        assertThat(second.memory().condensedQuestion()).isEqualTo("七天无理由退货的运费谁出");
        assertThat(second.rag().query()).as("检索用的是改写后的问题").isEqualTo("七天无理由退货的运费谁出");
        assertThat(second.sources()).anySatisfy(s -> assertThat(s.headingPath()).contains("退货运费"));

        Prompt answer = model.answerPrompts().getLast();
        assertThat(answer.getInstructions())
                .extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
        assertThat(answer.getInstructions().get(1).getText()).isEqualTo("七天无理由退货要什么条件");
        assertThat(answer.getUserMessage().getText()).contains("<参考资料>").contains("用户的问题：七天无理由退货的运费谁出");
        assertThat(stored(ConversationOwner.ANONYMOUS))
                .extracting(MemoryMessages::text)
                .as("记忆里存的是用户原话，不是改写后的")
                .contains("那运费谁出");
    }

    /** 第一句不改写（没有历史）；改写得不可信（这里是结果太长，模型直接开始回答了）时用原话检索，照样回答。 */
    @Test
    void condenseOnlyWithHistoryAndFallsBackWhenImplausible() {
        model.willClassify(POLICY);
        model.willReply("签收后 7 天内[1]。");
        ChatReply first = service().chat("七天无理由退货要什么条件", ID, null);
        assertThat(model.condensePrompts()).isEmpty();
        assertThat(first.memory().condensedQuestion()).isNull();

        model.willCondense("非质量问题的退货运费由买家承担，质量问题由商城承担。".repeat(10));
        ChatReply second = service().chat("退货运费谁出", ID, null);
        assertThat(second.memory().condensedQuestion()).isNull();
        assertThat(second.rag().query()).isEqualTo("退货运费谁出");
    }

    /**
     * 审查修复：改写这一步模型就调不通了——不再拿原话去调意图识别（同一个挂掉的模型再等一轮超时，一轮白等两次），这一轮直接失败；
     * 会话要放开（下一句还能问），半截的一轮不进记忆。
     */
    @Test
    void modelDownDuringCondenseSkipsIntentAndFailsFast() {
        model.willClassify(POLICY);
        model.willReply("签收后 7 天内[1]。");
        service().chat("七天无理由退货要什么条件", ID, null);
        int classifications = model.classificationPrompts().size();
        int stored = stored(ConversationOwner.ANONYMOUS).size();

        model.willFailCondense(new ResourceAccessException("连不上", new ConnectException("Connection refused")));

        assertThatThrownBy(() -> service().chat("退货运费谁出", ID, null))
                .isInstanceOfSatisfying(
                        ModelCallException.class,
                        e -> assertThat(e.failure()).isEqualTo(ModelFailure.MODEL_UNAVAILABLE));
        assertThat(model.classificationPrompts()).as("改写失败后没有再去调意图识别").hasSize(classifications);
        assertThat(stored(ConversationOwner.ANONYMOUS)).as("失败的一轮不进记忆").hasSize(stored);

        // 会话没有被卡住：模型恢复后同一个会话照常回答
        model.willFailCondense(null);
        model.willCondense("七天无理由退货的运费谁出");
        model.willReply("买家承担[1]。");
        assertThat(service().chat("那运费谁出", ID, null).reply()).contains("买家承担");
    }

    /** 订单号沿用：第二句「它什么时候下的单」没带订单号，从上一句沿用，照样直接查这一笔（而不是列出最近的订单）。 */
    @Test
    void orderNoIsCarriedToFollowUp() {
        model.willClassify(ORDER_WITH_NO, ORDER_WITHOUT_NO);
        model.willReply("您的订单待发货。");
        service().chat("订单 " + StubMallApi.ORDER_NO + " 到哪了", ID, StubMallApi.TOKEN);

        model.willReply("您在 2026-11-05 15:58 下的单。");
        ChatReply second = service().chat("它什么时候下的单", ID, StubMallApi.TOKEN);

        assertThat(second.intent().orderNo()).isEqualTo(StubMallApi.ORDER_NO);
        assertThat(second.memory().carriedOrderNo()).isEqualTo(StubMallApi.ORDER_NO);
        assertThat(second.tools()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("queryOrderStatus");
            assertThat(t.source()).isEqualTo(ToolInvocation.Source.DIRECT);
            assertThat(t.arguments()).contains(StubMallApi.ORDER_NO);
        });
        assertThat(FakeChatModel.allText(model.answerPrompts().getLast()))
                .contains("\"createdAt\":\"2026-11-05 15:58\"")
                .doesNotContain(StubMallApi.TOKEN);
    }

    /**
     * 意图识别看的是改写后的问题：「那能抵多少钱」单独看像问商品（实测 1.5b 就分成了 PRODUCT_INFO），改写成「积分能抵多少钱」再识别。
     */
    @Test
    void intentIsClassifiedOnCondensedQuestion() {
        model.willClassify(POLICY);
        model.willReply("12 个月[1]。");
        service().chat("积分多久过期", ID, null);

        model.willCondense("积分能抵多少钱");
        ChatReply second = service().chat("那能抵多少钱", ID, null);

        assertThat(model.classificationPrompts().getLast().getUserMessage().getText())
                .contains("积分能抵多少钱");
        assertThat(second.memory().condensedQuestion()).isEqualTo("积分能抵多少钱");
        assertThat(stored(ConversationOwner.ANONYMOUS))
                .extracting(MemoryMessages::text)
                .contains("那能抵多少钱");
    }

    /**
     * 模型抄错了订单号（改写或意图识别时少抄、错抄一位，格式照样合法）：不在原话和历史里的号不可信，扔掉，改用历史里原样出现过的那个。
     */
    @Test
    void miscopiedOrderNoIsReplacedByHistory() {
        model.willClassify(ORDER_WITH_NO);
        model.willReply("您的订单待发货。");
        service().chat("订单 " + StubMallApi.ORDER_NO + " 到哪了", ID, StubMallApi.TOKEN);

        String miscopied = StubMallApi.OTHER_USERS_ORDER_NO;
        model.willCondense("订单 " + miscopied + " 是什么时候下的单");
        model.willClassify(
                "{\"intent\":\"ORDER_STATUS\",\"confidence\":0.9,\"orderNo\":\"" + miscopied + "\",\"skuId\":null}");
        ChatReply second = service().chat("它什么时候下的单", ID, StubMallApi.TOKEN);

        assertThat(second.intent().orderNo()).isEqualTo(StubMallApi.ORDER_NO);
        assertThat(second.memory().carriedOrderNo()).isEqualTo(StubMallApi.ORDER_NO);
        assertThat(second.tools())
                .singleElement()
                .satisfies(t -> assertThat(t.arguments()).contains(StubMallApi.ORDER_NO));
    }

    /** 拒答也是一轮，照样记下（下一轮模型才知道上一句被拒了）。 */
    @Test
    void refusalIsRemembered() {
        model.willClassify(OUT_OF_SCOPE);

        ChatReply reply = service().chat("帮我写一首诗", ID, null);

        assertThat(model.answerPrompts()).isEmpty();
        assertThat(stored(ConversationOwner.ANONYMOUS))
                .extracting(MemoryMessages::text)
                .containsExactly("帮我写一首诗", reply.reply());
    }

    /** 模型调用失败：异常照常抛出（503），这一轮不写进记忆，会话也放开了。 */
    @Test
    void failedTurnIsNotStored() {
        model.willFail(new ResourceAccessException("连不上", new ConnectException("Connection refused")));

        assertThatThrownBy(() -> service().chat("你好", ID, null)).isInstanceOf(ModelCallException.class);

        assertThat(stored(ConversationOwner.ANONYMOUS)).isEmpty();
        model.reset();
        assertThat(service().chat("你好", ID, null).memory().active()).isTrue();
    }

    /** 隔离：用户 10002 拿着 10001 的会话 ID 来问，提示词里没有 10001 的历史；10001 的记忆也没被写进东西。 */
    @Test
    void anotherUserCannotSeeOrWriteTheConversation() {
        model.willReply("您的订单待发货。");
        model.willClassify(ORDER_WITH_NO);
        service().chat("订单 " + StubMallApi.ORDER_NO + " 到哪了", ID, StubMallApi.TOKEN);

        model.willClassify(ORDER_WITHOUT_NO);
        ChatReply stranger = service().chat("它什么时候下的单", ID, StubMallApi.NEW_USER_TOKEN);

        assertThat(stranger.memory().historyTurns()).isZero();
        assertThat(stranger.memory().carriedOrderNo()).isNull();
        assertThat(FakeChatModel.allText(model.answerPrompts().getLast())).doesNotContain(StubMallApi.ORDER_NO);
        assertThat(stored(ConversationOwner.user("10001"))).hasSize(2);
        assertThat(stored(ConversationOwner.user("10002"))).hasSize(2);
    }

    /** 流式：最前面是记忆情况，文字推完、流正常结束之后才写进记忆（拼起来的完整回答）。 */
    @Test
    void streamSavesAfterCompletion() {
        model.willStream("您好，", "我是小示。");

        ChatTurn turn = service().begin("你好", ID, null);
        List<AnswerPart> parts = service().answerStream(turn).collectList().block();

        assertThat(parts.getFirst()).isInstanceOf(AnswerPart.Memory.class);
        assertThat(((AnswerPart.Memory) parts.getFirst()).trace().active()).isTrue();
        assertThat(parts.subList(1, parts.size()))
                .containsExactly(new AnswerPart.Text("您好，"), new AnswerPart.Text("我是小示。"));
        assertThat(stored(ConversationOwner.ANONYMOUS))
                .extracting(MemoryMessages::text)
                .containsExactly("你好", "您好，我是小示。");
    }

    /** 流式 RAG 追问：记忆情况在改写之后推出（带着改写结果），然后才是引用来源和文字。 */
    @Test
    void streamRagFollowUpEmitsMemoryAfterCondense() {
        model.willClassify(POLICY);
        model.willReply("签收后 7 天内[1]。");
        service().chat("七天无理由退货要什么条件", ID, null);

        model.willCondense("七天无理由退货的运费谁出");
        model.willStream("买家承担", "[1]。");
        List<AnswerPart> parts = service()
                .answerStream(service().begin("那运费谁出", ID, null))
                .collectList()
                .block();

        assertThat(parts.getFirst())
                .isInstanceOfSatisfying(
                        AnswerPart.Memory.class,
                        m -> assertThat(m.trace().condensedQuestion()).isEqualTo("七天无理由退货的运费谁出"));
        assertThat(parts.get(1)).isInstanceOf(AnswerPart.Source.class);
        assertThat(parts.getLast()).isEqualTo(new AnswerPart.Text("[1]。"));
        assertThat(stored(ConversationOwner.ANONYMOUS))
                .extracting(MemoryMessages::text)
                .contains("买家承担。");
    }

    /** 流式中途出错：不写记忆，会话放开（下一句能正常开始）。 */
    @Test
    void streamFailureIsNotStored() {
        model.willStream("您好，");
        model.willFail(new ResourceAccessException("断了", new ConnectException("Connection reset")));

        StepVerifier.create(service().answerStream(service().begin("你好", ID, null)))
                .expectNextMatches(AnswerPart.Memory.class::isInstance)
                .expectNext(new AnswerPart.Text("您好，"))
                .expectError(ModelCallException.class)
                .verify();

        assertThat(stored(ConversationOwner.ANONYMOUS)).isEmpty();
        service().begin("你好", ID, null).conversation().close();
    }

    /** 流式被取消（用户关了页面）：不写记忆，会话放开。 */
    @Test
    void cancelledStreamIsNotStored() {
        model.willStream(Flux.just("您好，").concatWith(Flux.never()));

        StepVerifier.create(service().answerStream(service().begin("你好", ID, null)))
                .expectNextMatches(AnswerPart.Memory.class::isInstance)
                .expectNext(new AnswerPart.Text("您好，"))
                .thenCancel()
                .verify();

        assertThat(stored(ConversationOwner.ANONYMOUS)).isEmpty();
        service().begin("你好", ID, null).conversation().close();
    }

    /** 不带会话 ID 的流式：没有记忆片段，和第 5 章一样。 */
    @Test
    void streamWithoutConversationHasNoMemoryPart() {
        List<AnswerPart> parts = service()
                .answerStream(service().begin("你好", null, null))
                .collectList()
                .block();

        assertThat(parts).noneMatch(AnswerPart.Memory.class::isInstance);
        assertThat(chat.memory().repository().findConversationIds()).isEmpty();
    }
}
