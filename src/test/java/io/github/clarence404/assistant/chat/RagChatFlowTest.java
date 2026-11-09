package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.intent.Intent;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.intent.IntentService;
import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallApiProperties;
import io.github.clarence404.assistant.prompt.TestPrompts;
import io.github.clarence404.assistant.rag.RagFixtures;
import io.github.clarence404.assistant.rag.RagSource;
import io.github.clarence404.assistant.rag.RagTrace;
import io.github.clarence404.assistant.tool.MallTools;
import io.github.clarence404.assistant.tool.ToolProperties;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.ResourceAccessException;
import reactor.test.StepVerifier;

/**
 * 第 5 章 TODO ch05-6 / ch05-8：对话按意图走 RAG——检索不到就短路、带编号资料调模型、引用校验；流式先推引用来源再推文字。
 *
 * <p>手法：同第 3 章的 {@code ToolCallingFlowTest}，不启动 Spring，手工组装 ChatService：模型是 {@link FakeChatModel}，
 * 知识库是假向量模型 + 6 篇真实规则文档（{@link RagFixtures}）。意图用 {@code willClassify} 预设。
 */
class RagChatFlowTest {

    private static final String POLICY = "{\"intent\":\"POLICY\",\"confidence\":0.9,\"orderNo\":null,\"skuId\":null}";
    private static final String AFTER_SALES =
            "{\"intent\":\"AFTER_SALES\",\"confidence\":0.9,\"orderNo\":null,\"skuId\":null}";
    private static final String TOKEN = "mall-token-should-never-reach-model";

    private static RagFixtures.Kb kb;

    @BeforeAll
    static void ingest() {
        kb = RagFixtures.fakeKnowledgeBase();
    }

    private static ChatService service(FakeChatModel model) {
        ChatClient chatClient = new ChatClientConfig().chatClient(ChatClient.builder(model));
        MallTools tools = new MallTools(new MallApiClient(
                new MallApiProperties("http://127.0.0.1:1", Duration.ofSeconds(1), Duration.ofSeconds(1))));
        return new ChatService(
                chatClient,
                new ChatProperties(Duration.ofSeconds(5), 0.6),
                TestPrompts.prompts("v3"),
                new IntentService(chatClient, new ClassPathResource("prompts/intent-v2.st")),
                tools,
                new ToolProperties(true, true),
                RagFixtures.service(chatClient, kb, RagFixtures.properties()));
    }

    /** 规则类问题：检索 → 带编号资料调一次回答模型（不注册工具）→ 回答、引用来源、调试信息都在。 */
    @Test
    void policyQuestionIsAnsweredFromNumberedSources() {
        FakeChatModel model = new FakeChatModel();
        model.willClassify(POLICY);
        model.willReply("签收后 7 天内、商品不影响二次销售就可以无理由退货[1]。");

        ChatReply reply = service(model).chat("七天无理由退货要什么条件", TOKEN);

        assertThat(reply.intent().intent()).isEqualTo(Intent.POLICY);
        assertThat(reply.reply()).isEqualTo("签收后 7 天内、商品不影响二次销售就可以无理由退货[1]。");
        assertThat(reply.tools()).isEmpty();
        assertThat(reply.sources()).isNotEmpty();
        assertThat(reply.sources()).extracting(RagSource::index).startsWith(1);
        assertThat(reply.sources().getFirst().headingPath()).contains("七天无理由退货");
        RagTrace trace = reply.rag();
        assertThat(trace.mode()).isEqualTo("hybrid");
        assertThat(trace.cited()).containsExactly(1);
        assertThat(trace.invalidCitations()).isEmpty();
        assertThat(trace.uncited()).isFalse();

        assertThat(model.answerPrompts()).singleElement().satisfies(prompt -> {
            assertThat(prompt.getSystemMessage().getText()).contains("参考资料");
            assertThat(prompt.getUserMessage().getText()).contains("<参考资料>", "[1] ");
            assertThat(FakeChatModel.toolNames(prompt)).isEmpty();
        });
    }

    /** 商城令牌和 RAG 无关：任何一次模型调用（意图识别、回答）的任何文字里都没有它。 */
    @Test
    void mallTokenNeverReachesModel() {
        FakeChatModel model = new FakeChatModel();
        model.willClassify(AFTER_SALES);
        model.willReply("质量问题可以退换[1]。");

        service(model).chat("东西坏了能退吗", TOKEN);

        assertThat(model.prompts()).hasSize(2);
        for (Prompt prompt : model.prompts()) {
            assertThat(FakeChatModel.allText(prompt)).doesNotContain(TOKEN);
        }
    }

    /** 回答里引用了不存在的编号：去掉，记进调试信息。 */
    @Test
    void nonexistentCitationsAreRemovedFromReply() {
        FakeChatModel model = new FakeChatModel();
        model.willClassify(POLICY);
        model.willReply("可以退[1]，运费另算[9]。");

        ChatReply reply = service(model).chat("七天无理由退货要什么条件", null);

        assertThat(reply.reply()).isEqualTo("可以退[1]，运费另算。");
        assertThat(reply.rag().invalidCitations()).containsExactly(9);
        assertThat(reply.rag().cited()).containsExactly(1);
    }

    @Test
    void replyWithoutCitationsIsFlaggedButKept() {
        FakeChatModel model = new FakeChatModel();
        model.willClassify(POLICY);
        model.willReply("七天内可以无理由退货。");

        ChatReply reply = service(model).chat("七天无理由退货要什么条件", null);

        assertThat(reply.reply()).isEqualTo("七天内可以无理由退货。");
        assertThat(reply.rag().uncited()).isTrue();
    }

    /** 知识库里没有相关资料：直接回固定话术，只调了意图识别，回答模型一次都没调。 */
    @Test
    void noRelevantSourcesShortCircuitsWithoutAnswerModel() {
        FakeChatModel model = new FakeChatModel();
        model.willClassify(POLICY);

        ChatReply reply = service(model).chat("火星探测器", null);

        assertThat(reply.reply()).contains("没有找到");
        assertThat(reply.sources()).isEmpty();
        assertThat(reply.rag().shortCircuit()).isEqualTo(RagTrace.NO_MATCH);
        assertThat(model.classificationPrompts()).hasSize(1);
        assertThat(model.answerPrompts()).isEmpty();
    }

    /** 不是规则类的意图照旧：系统提示词是 v3，没有引用来源，rag 为 null。 */
    @Test
    void otherIntentsKeepChapterThreePath() {
        FakeChatModel model = new FakeChatModel();
        model.willReply("您好！");

        ChatReply reply = service(model).chat("你好", null);

        assertThat(reply.sources()).isEmpty();
        assertThat(reply.rag()).isNull();
        assertThat(model.lastPrompt().getSystemMessage().getText()).doesNotContain("参考资料");
    }

    @Test
    void outOfScopeIsStillRefusedBeforeRag() {
        FakeChatModel model = new FakeChatModel();
        model.willClassify("{\"intent\":\"OUT_OF_SCOPE\",\"confidence\":0.95,\"orderNo\":null,\"skuId\":null}");

        ChatReply reply = service(model).chat("帮我写首诗", null);

        assertThat(reply.rag()).isNull();
        assertThat(model.answerPrompts()).isEmpty();
    }

    /** 命令行模型那样不支持工具调用的模型：RAG 只需要读写文字，照样能答。 */
    @Test
    void worksWithModelsWithoutToolCalling() {
        FakeChatModel model = FakeChatModel.withoutToolCalling();
        model.willReply("积分有效期 12 个月[1]。");

        ChatReply reply = service(model).answer("七天无理由退货要什么条件", new IntentResult(Intent.POLICY, 0.9, null, null), null);

        assertThat(reply.reply()).isEqualTo("积分有效期 12 个月[1]。");
        assertThat(reply.sources()).isNotEmpty();
    }

    @Test
    void modelFailureIsClassified() {
        FakeChatModel model = new FakeChatModel();
        model.willFail(new ResourceAccessException("connect", new ConnectException("refused")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service(model).answer("七天无理由退货要什么条件", new IntentResult(Intent.POLICY, 0.9, null, null), null))
                .isInstanceOf(ModelCallException.class);
    }

    /** 流式：先按编号推出每条引用来源，再推模型的文字。 */
    @Test
    void streamSendsSourcesBeforeText() {
        FakeChatModel model = new FakeChatModel();
        model.willStream("积分有效期", " 12 个月[1]。");
        ChatService service = service(model);
        IntentResult intent = new IntentResult(Intent.POLICY, 0.9, null, null);
        int sources = service.ragAnswer("七天无理由退货要什么条件", intent).sources().size();
        model.reset();
        model.willStream("积分有效期", " 12 个月[1]。");

        List<AnswerPart> parts = service.answerStream("七天无理由退货要什么条件", intent, TOKEN)
                .collectList()
                .block();

        assertThat(parts).hasSize(sources + 2);
        for (int i = 0; i < sources; i++) {
            AnswerPart part = parts.get(i);
            assertThat(part).isInstanceOf(AnswerPart.Source.class);
            assertThat(((AnswerPart.Source) part).source().index()).isEqualTo(i + 1);
        }
        assertThat(parts.subList(sources, parts.size()))
                .containsExactly(new AnswerPart.Text("积分有效期"), new AnswerPart.Text(" 12 个月[1]。"));
        assertThat(FakeChatModel.allText(model.lastPrompt())).doesNotContain(TOKEN);
    }

    @Test
    void streamShortCircuitSendsOnlyFallbackText() {
        FakeChatModel model = new FakeChatModel();

        StepVerifier.create(service(model)
                        .answerStream("火星探测器", new IntentResult(Intent.AFTER_SALES, 0.9, null, null), null))
                .assertNext(part -> assertThat(((AnswerPart.Text) part).text()).contains("没有找到"))
                .verifyComplete();
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    void streamFailureEndsWithModelCallException() {
        FakeChatModel model = new FakeChatModel();
        model.willStream("积分");
        model.willFail(new ResourceAccessException("connect", new ConnectException("refused")));

        StepVerifier.create(service(model)
                        .answerStream("七天无理由退货要什么条件", new IntentResult(Intent.POLICY, 0.9, null, null), null))
                .thenConsumeWhile(part -> part instanceof AnswerPart.Source)
                .expectNext(new AnswerPart.Text("积分"))
                .verifyError(ModelCallException.class);
    }
}
