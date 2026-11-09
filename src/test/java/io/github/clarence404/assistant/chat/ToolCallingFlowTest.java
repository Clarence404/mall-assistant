package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.intent.Intent;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.intent.IntentService;
import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallApiProperties;
import io.github.clarence404.assistant.mall.StubMallApi;
import io.github.clarence404.assistant.prompt.TestPrompts;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.github.clarence404.assistant.tool.MallTools;
import io.github.clarence404.assistant.tool.OrderListLookup;
import io.github.clarence404.assistant.tool.ToolInvocation;
import io.github.clarence404.assistant.tool.ToolProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ClassPathResource;
import reactor.test.StepVerifier;

/**
 * 第 3 章：一次对话里工具是怎么用上的——模型自己决定调用（原生工具调用）、按意图直接查询、命令行模型那样不支持工具调用的模型。
 *
 * <p>验证：
 *
 * <ul>
 *   <li>原生工具调用：假模型第一轮返回「调用 queryOrderStatus，参数 {orderNo}」，Spring AI 执行工具（真的调了假商城、带着令牌），
 *       把结果作为工具结果消息交回，第二轮假模型才给出回答；工具清单里有三个工具；记录里来源是 MODEL
 *   <li>按意图直接查询：意图是带订单号的 ORDER_STATUS 时，工具只执行一次、来源是 DIRECT，查询结果进了用户消息，回答只调一次模型、不再注册工具；
 *       没带订单号时（「我有哪些订单」，第 3 章补充）直接执行 listMyOrders，最近的订单作为查询结果交给模型，没登录时是「请先登录」
 *   <li>令牌：只在 ToolContext 里，不在任何发给模型的文字里（消息、工具参数、工具结果、工具定义）
 *   <li>没登录、参数不合法：工具返回结构化的原因，不发商城请求；回答里编出订单状态时被事后检查改写
 *   <li>不支持工具调用的模型（{@code native-calling=false}）：不注册工具，只走直接查询
 *   <li>流式：工具调用记录排在文字前面
 * </ul>
 *
 * <p>手法：不启动 Spring，手工组装 ChatService，模型是 {@link FakeChatModel}（用 {@code willCallTool} 预设调用意图），商城是
 * {@link StubMallApi}。工具调用循环用的是 Spring AI 自己的 {@code ToolCallingAdvisor}（ChatClient 自动注册），不是测试里模拟的。
 */
class ToolCallingFlowTest {

    private static final String ORDER_ARGS = "{\"orderNo\":\"" + StubMallApi.ORDER_NO + "\"}";
    private static final String ORDER_INTENT = "{\"intent\":\"ORDER_STATUS\",\"confidence\":0.9,\"orderNo\":\""
            + StubMallApi.ORDER_NO + "\",\"skuId\":null}";
    /** 查订单但没抽到订单号，如「我有哪些订单」「我昨天买的东西到哪了」。 */
    private static final String ORDER_INTENT_WITHOUT_NO =
            "{\"intent\":\"ORDER_STATUS\",\"confidence\":0.9,\"orderNo\":null,\"skuId\":null}";

    private static final String PRODUCT_INTENT =
            "{\"intent\":\"PRODUCT_INFO\",\"confidence\":0.9,\"orderNo\":null,\"skuId\":\"20001\"}";

    private static StubMallApi mall;
    private static MallTools tools;

    private FakeChatModel model;

    @BeforeAll
    static void startMall() {
        mall = StubMallApi.start();
        tools = new MallTools(
                new MallApiClient(new MallApiProperties(mall.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(5))));
    }

    @AfterAll
    static void stopMall() {
        mall.close();
    }

    @BeforeEach
    void setUp() {
        mall.reset();
        model = new FakeChatModel();
    }

    /** 组装 ChatService：系统提示词 v3、固定日期，工具开关按参数。 */
    private ChatService service(FakeChatModel chatModel, boolean nativeCalling, boolean directLookup) {
        ChatClient chatClient = new ChatClientConfig().chatClient(ChatClient.builder(chatModel));
        IntentService intentService = new IntentService(chatClient, new ClassPathResource("prompts/intent-v2.st"));
        return new ChatService(
                chatClient,
                new ChatProperties(Duration.ofSeconds(5), 0.6),
                TestPrompts.prompts("v3"),
                intentService,
                tools,
                new ToolProperties(nativeCalling, directLookup),
                RagFixtures.disabled(chatClient));
    }

    @Test
    void modelDecidesToCallToolAndGetsResultBack() {
        model.willCallTool("queryOrderStatus", ORDER_ARGS);
        model.willReply("您的订单目前是「待发货」。");

        ChatReply reply = service(model, true, true).chat("帮我看看我那笔红心柚的订单 " + StubMallApi.ORDER_NO, StubMallApi.TOKEN);

        assertThat(reply.reply()).isEqualTo("您的订单目前是「待发货」。");
        assertThat(reply.tools()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("queryOrderStatus");
            assertThat(t.source()).isEqualTo(ToolInvocation.Source.MODEL);
            assertThat(t.arguments()).isEqualTo(ORDER_ARGS);
            assertThat(t.result()).contains("\"found\":true").contains("待发货");
        });
        List<Prompt> answers = model.answerPrompts();
        assertThat(answers).as("第一轮拿到调用意图，第二轮带着工具结果再问一次").hasSize(2);
        assertThat(FakeChatModel.toolNames(answers.getFirst()))
                .containsExactly("listMyOrders", "queryOrderStatus", "queryProduct");
        assertThat(FakeChatModel.toolResponses(answers.getFirst())).isEmpty();
        assertThat(FakeChatModel.toolResponses(answers.getLast()))
                .singleElement()
                .asString()
                .contains("待发货")
                .contains("青柚 · 红心柚 2 只装 ×1");
        assertThat(mall.requests())
                .singleElement()
                .satisfies(r -> assertThat(r.authorization()).isEqualTo("Bearer " + StubMallApi.TOKEN));
    }

    /** 令牌在 ToolContext 里（工具拿得到），但不在任何发给模型的文字里。 */
    @Test
    void tokenStaysInToolContextAndNeverReachesTheModel() {
        model.willCallTool("queryOrderStatus", ORDER_ARGS);
        service(model, true, true).chat("查一下订单 " + StubMallApi.ORDER_NO, StubMallApi.TOKEN);
        model.reset();
        model.willClassify(ORDER_INTENT);
        service(model, true, true).chat("查一下订单 " + StubMallApi.ORDER_NO, StubMallApi.TOKEN);

        assertThat(mall.requests()).hasSize(2);
        assertThat(model.prompts()).isNotEmpty();
        // 两次对话（原生工具调用、直接查询）发给模型的全部内容里都没有令牌
        assertThat(model.prompts())
                .allSatisfy(p -> assertThat(FakeChatModel.allText(p)).doesNotContain(StubMallApi.TOKEN));
    }

    @Test
    void toolContextOfTheRequestCarriesTheToken() {
        model.willCallTool("queryOrderStatus", ORDER_ARGS);

        service(model, true, true).chat("查一下订单 " + StubMallApi.ORDER_NO, StubMallApi.TOKEN);

        Prompt first = model.answerPrompts().getFirst();
        assertThat(first.getOptions())
                .isInstanceOfSatisfying(
                        ToolCallingChatOptions.class,
                        o -> assertThat(o.getToolContext()).containsEntry(MallTools.MALL_TOKEN, StubMallApi.TOKEN));
        assertThat(FakeChatModel.allText(first)).doesNotContain(StubMallApi.TOKEN);
    }

    /** 意图识别已经抽到订单号：代码直接查一次，结果放进用户消息，只调一次回答模型，不再注册工具。 */
    @Test
    void orderStatusWithOrderNoIsLookedUpDirectly() {
        model.willClassify(ORDER_INTENT);
        model.willReply("您的订单目前是「待发货」。");

        ChatReply reply = service(model, true, true).chat("我的订单 " + StubMallApi.ORDER_NO + " 到哪了", StubMallApi.TOKEN);

        assertThat(reply.tools()).singleElement().satisfies(t -> {
            assertThat(t.source()).isEqualTo(ToolInvocation.Source.DIRECT);
            assertThat(t.arguments()).isEqualTo(ORDER_ARGS);
        });
        assertThat(mall.requests()).hasSize(1);
        assertThat(model.answerPrompts()).singleElement().satisfies(p -> {
            assertThat(FakeChatModel.toolNames(p)).as("已经查过了，不再给模型工具").isEmpty();
            assertThat(p.getUserMessage().getText())
                    .startsWith("我的订单 " + StubMallApi.ORDER_NO + " 到哪了")
                    .contains("<查询结果>")
                    .contains("待发货")
                    .contains("系统暂不提供物流轨迹");
        });
    }

    /**
     * 第 3 章补充：查订单却没给订单号。代码直接执行一次 listMyOrders，最近的订单（含状态、商品）作为查询结果进了用户消息，
     * 模型据此把订单列出来、问用户要看哪一笔；只调一次回答模型，不注册工具；令牌只用在商城请求里，不在任何发给模型的文字里。
     */
    @Test
    void orderStatusWithoutOrderNoListsMyOrdersDirectly() {
        model.willClassify(ORDER_INTENT_WITHOUT_NO);
        model.willReply("您最近有两笔订单，想看哪一笔？");

        ChatReply reply = service(model, true, true).chat("我有哪些订单", StubMallApi.TOKEN);

        assertThat(reply.tools()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("listMyOrders");
            assertThat(t.source()).isEqualTo(ToolInvocation.Source.DIRECT);
            assertThat(t.arguments()).isEqualTo("{\"size\":" + MallTools.DEFAULT_LIST_SIZE + "}");
            assertThat(t.result()).contains("\"found\":true").contains(StubMallApi.COMPLETED_ORDER_NO);
        });
        assertThat(mall.requests())
                .as("1 次列表 + 2 次补查商品，都带着用户的令牌")
                .hasSize(3)
                .allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer " + StubMallApi.TOKEN));
        assertThat(model.answerPrompts()).singleElement().satisfies(p -> {
            assertThat(FakeChatModel.toolNames(p)).as("已经查过了，不再给模型工具").isEmpty();
            assertThat(p.getUserMessage().getText())
                    .startsWith("我有哪些订单")
                    .contains("<查询结果>")
                    .contains("用户看不到它")
                    .contains(StubMallApi.ORDER_NO)
                    .contains("待发货")
                    .contains("已完成")
                    .contains("青柚 · 柚子茶 500g ×1")
                    .contains(OrderListLookup.LIST_NOTE);
        });
        assertThat(model.prompts())
                .allSatisfy(p -> assertThat(FakeChatModel.allText(p)).doesNotContain(StubMallApi.TOKEN));
        assertThat(reply.reply()).isEqualTo("您最近有两笔订单，想看哪一笔？");
    }

    @Test
    void orderListWithoutLoginSaysPleaseLoginWithoutCallingMall() {
        model.willClassify(ORDER_INTENT_WITHOUT_NO);

        ChatReply reply = service(model, true, true).chat("我昨天买的东西到哪了", null);

        assertThat(mall.requests()).isEmpty();
        assertThat(reply.tools()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("listMyOrders");
            assertThat(t.result()).contains("\"found\":false").contains("请先在页面右上角登录");
        });
        assertThat(model.answerPrompts().getFirst().getUserMessage().getText()).contains("请先在页面右上角登录");
    }

    @Test
    void productInfoWithSkuIsLookedUpDirectlyWithoutLogin() {
        model.willClassify(PRODUCT_INTENT);

        ChatReply reply = service(model, true, true).chat("20001 有货吗", null);

        assertThat(reply.tools()).extracting(ToolInvocation::name).containsExactly("queryProduct");
        assertThat(model.answerPrompts().getFirst().getUserMessage().getText())
                .contains("青柚 · 红心柚 2 只装")
                .contains("\"inStock\":true");
    }

    @Test
    void notLoggedInLookupSaysPleaseLoginWithoutCallingMall() {
        model.willClassify(ORDER_INTENT);

        ChatReply reply = service(model, true, true).chat("我的订单 " + StubMallApi.ORDER_NO + " 到哪了", null);

        assertThat(mall.requests()).isEmpty();
        assertThat(reply.tools().getFirst().result())
                .contains("\"found\":false")
                .contains("登录");
        assertThat(model.answerPrompts().getFirst().getUserMessage().getText()).contains("登录");
    }

    /** 模型无视「没查到」的结果、编了一个状态：事后检查换成工具给出的原因。 */
    @Test
    void inventedStatusIsReplacedWhenLookupFoundNothing() {
        model.willClassify(ORDER_INTENT);
        model.willReply("您的订单已发货，预计明天送达。");

        ChatReply reply = service(model, true, true).chat("我的订单 " + StubMallApi.ORDER_NO + " 到哪了", null);

        assertThat(reply.reply()).startsWith("抱歉，").contains("登录").doesNotContain("已发货");
    }

    /** 模型抄错了订单号：工具返回「格式不对」交回给模型，不拿去调商城。 */
    @Test
    void malformedArgumentsFromModelBecomeStructuredError() {
        model.willCallTool("queryOrderStatus", "{\"orderNo\":\"12345\"}");

        ChatReply reply = service(model, true, true).chat("查订单 12345", StubMallApi.TOKEN);

        assertThat(mall.requests()).isEmpty();
        assertThat(FakeChatModel.toolResponses(model.answerPrompts().getLast()))
                .singleElement()
                .asString()
                .contains("格式不对");
        assertThat(reply.tools()).hasSize(1);
    }

    /** 命令行模型：不支持工具调用，profile 关掉 native-calling；能直接查的照样查，查不了的就不用工具。 */
    @Test
    void modelWithoutToolCallingUsesDirectLookupOnly() {
        FakeChatModel cli = FakeChatModel.withoutToolCalling();
        cli.willClassify(ORDER_INTENT);
        ChatService service = service(cli, false, true);

        ChatReply direct = service.chat("我的订单 " + StubMallApi.ORDER_NO + " 到哪了", StubMallApi.TOKEN);
        assertThat(direct.tools()).extracting(ToolInvocation::source).containsExactly(ToolInvocation.Source.DIRECT);

        cli.reset();
        cli.willCallTool("queryOrderStatus", ORDER_ARGS); // 就算模型「想」调，也没有工具可调
        ChatReply plain = service.chat("我的订单到哪了", StubMallApi.TOKEN);
        assertThat(plain.tools()).isEmpty();
        assertThat(cli.answerPrompts())
                .singleElement()
                .satisfies(p -> assertThat(FakeChatModel.toolNames(p)).isEmpty());
    }

    /** 命令行模型调不了工具：没订单号的订单问题也能经直接查询列出订单（第 3 章补充前，这类问题它只能反问订单号）。 */
    @Test
    void modelWithoutToolCallingListsOrdersDirectly() {
        FakeChatModel cli = FakeChatModel.withoutToolCalling();
        cli.willClassify(ORDER_INTENT_WITHOUT_NO);

        ChatReply reply = service(cli, false, true).chat("我有哪些订单", StubMallApi.TOKEN);

        assertThat(reply.tools()).extracting(ToolInvocation::name).containsExactly("listMyOrders");
        assertThat(cli.answerPrompts().getFirst().getUserMessage().getText()).contains(StubMallApi.ORDER_NO);
    }

    @Test
    void directLookupCanBeTurnedOff() {
        model.willClassify(ORDER_INTENT);

        ChatReply reply = service(model, true, false).chat("我的订单 " + StubMallApi.ORDER_NO + " 到哪了", StubMallApi.TOKEN);

        assertThat(reply.tools()).isEmpty();
        assertThat(FakeChatModel.toolNames(model.answerPrompts().getFirst())).hasSize(3);
        assertThat(model.answerPrompts().getFirst().getUserMessage().getText()).doesNotContain("<查询结果>");
    }

    /** 流式：模型决定调工具，工具调用记录先推出来，然后才是回答的文字。 */
    @Test
    void streamEmitsToolUseBeforeText() {
        model.willCallTool("queryOrderStatus", ORDER_ARGS);
        model.willStream("您的订单", "待发货。");

        StepVerifier.create(service(model, true, true)
                        .answerStream("查订单 " + StubMallApi.ORDER_NO, IntentResult.unknown(), StubMallApi.TOKEN))
                .assertNext(part -> assertThat(part)
                        .isInstanceOfSatisfying(
                                AnswerPart.ToolUse.class,
                                t -> assertThat(t.invocation().name()).isEqualTo("queryOrderStatus")))
                .expectNext(new AnswerPart.Text("您的订单"), new AnswerPart.Text("待发货。"))
                .verifyComplete();
    }

    @Test
    void streamWithDirectOrderListStartsWithToolUse() {
        model.willStream("您最近有两笔订单，", "想看哪一笔？");

        StepVerifier.create(service(model, true, true)
                        .answerStream(
                                "我有哪些订单", new IntentResult(Intent.ORDER_STATUS, 0.9, null, null), StubMallApi.TOKEN))
                .assertNext(part -> assertThat(part)
                        .isInstanceOfSatisfying(
                                AnswerPart.ToolUse.class,
                                t -> assertThat(t.invocation().name()).isEqualTo("listMyOrders")))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void streamWithDirectLookupStartsWithToolUse() {
        model.willStream("您的订单", "待发货。");

        StepVerifier.create(service(model, true, true)
                        .answerStream(
                                "我的订单 " + StubMallApi.ORDER_NO + " 到哪了",
                                new IntentResult(Intent.ORDER_STATUS, 0.9, StubMallApi.ORDER_NO, null),
                                StubMallApi.TOKEN))
                .assertNext(part -> assertThat(part)
                        .isInstanceOfSatisfying(
                                AnswerPart.ToolUse.class,
                                t -> assertThat(t.invocation().source()).isEqualTo(ToolInvocation.Source.DIRECT)))
                .expectNextCount(2)
                .verifyComplete();
        assertThat(model.answerPrompts())
                .singleElement()
                .satisfies(p -> assertThat(p.getUserMessage().getText()).contains("<查询结果>"));
    }
}
