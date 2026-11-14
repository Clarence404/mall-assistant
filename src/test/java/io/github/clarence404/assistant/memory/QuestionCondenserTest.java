package io.github.clarence404.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.chat.ChatClientConfig;
import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import java.net.ConnectException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;

/**
 * 第 6 章 TODO ch06-3：追问改写——有历史时调一次模型，把「那运费谁出」改写成独立问题；没历史、关着开关时不调；输出要清理，明显不对或调用失败就退回原话。
 *
 * <p>手法：{@link FakeChatModel#willCondense} 预设模型的原样输出，断言发给模型的提示词结构和清理后的结果。
 */
class QuestionCondenserTest {

    private static final Instant AT = MemoryFixtures.NOW;

    private static final List<Message> HISTORY =
            List.of(MemoryMessages.user("七天无理由退货要什么条件", AT), MemoryMessages.assistant("签收后 7 天内、商品不影响二次销售就可以申请。", AT));

    private static QuestionCondenser condenser(FakeChatModel model, MemoryProperties properties) {
        return new QuestionCondenser(new ChatClientConfig().chatClient(ChatClient.builder(model)), properties);
    }

    /** 有历史：调一次模型，提示词里有改写规则、带标签的历史和最新一句；温度 0；不注册工具。 */
    @Test
    void followUpIsCondensedWithHistory() {
        FakeChatModel model = new FakeChatModel();
        model.willCondense("七天无理由退货的运费由谁承担");

        String condensed = condenser(model, MemoryFixtures.properties()).condense("那运费谁出", HISTORY);

        assertThat(condensed).isEqualTo("七天无理由退货的运费由谁承担");
        assertThat(model.condensePrompts()).hasSize(1);
        Prompt prompt = model.condensePrompts().getFirst();
        assertThat(prompt.getSystemMessage().getText())
                .contains("不看上文也能看懂的完整问题")
                .contains("不要回答问题");
        assertThat(prompt.getInstructions())
                .as("系统消息之后是 3 组示例（一问一答），最后才是这一句")
                .hasSize(1 + QuestionCondenser.EXAMPLES.size() * 2 + 1);
        assertThat(prompt.getInstructions().get(2).getText()).isEqualTo("会员等级怎么升级");
        assertThat(prompt.getUserMessage().getText())
                .contains("<对话历史>")
                .contains("用户：七天无理由退货要什么条件")
                .contains("助手：签收后 7 天内")
                .contains("最新一句：那运费谁出");
        assertThat(prompt.getOptions().getTemperature()).isEqualTo(0.0);
        assertThat(FakeChatModel.toolNames(prompt)).isEmpty();
    }

    /** 没有历史：第一句话不用改写，不调模型。 */
    @Test
    void noHistoryNoCall() {
        FakeChatModel model = new FakeChatModel();

        assertThat(condenser(model, MemoryFixtures.properties()).condense("七天无理由退货要什么条件", List.of()))
                .isNull();
        assertThat(model.prompts()).isEmpty();
    }

    /** 开关关着：不调模型。 */
    @Test
    void disabledNoCall() {
        FakeChatModel model = new FakeChatModel();
        MemoryProperties p = MemoryFixtures.properties();
        MemoryProperties off = new MemoryProperties(
                p.enabled(),
                p.store(),
                p.ttl(),
                p.sweepInterval(),
                p.maxMessages(),
                p.windowTurns(),
                p.budgetChars(),
                p.entityTurns(),
                new MemoryProperties.Condense(false, 3, 120),
                p.summary(),
                p.ownerCacheTtl(),
                p.jdbc());

        assertThat(condenser(model, off).condense("那运费谁出", HISTORY)).isNull();
        assertThat(model.prompts()).isEmpty();
    }

    /** 小模型常见的多余输出：前缀、引号、后面跟着解释——只取第一行、去掉前缀和引号。 */
    @Test
    void outputIsCleaned() {
        FakeChatModel model = new FakeChatModel();
        model.willCondense("改写后的问题：「七天无理由退货的运费谁出」\n这是因为用户上文问的是七天无理由退货。");

        assertThat(condenser(model, MemoryFixtures.properties()).condense("那运费谁出", HISTORY))
                .isEqualTo("七天无理由退货的运费谁出");
    }

    /** 改写结果太长（模型开始回答问题了）、为空、和原话一样：都当作没改写。 */
    @Test
    void implausibleOutputFallsBackToOriginal() {
        FakeChatModel model = new FakeChatModel();
        QuestionCondenser condenser = condenser(model, MemoryFixtures.properties());

        model.willCondense("七天无理由退货的运费：" + "如果是质量问题由商城承担，否则由买家承担，".repeat(10));
        assertThat(condenser.condense("那运费谁出", HISTORY)).isNull();
        model.willCondense("   ");
        assertThat(condenser.condense("那运费谁出", HISTORY)).isNull();
        model.willCondense("那运费谁出");
        assertThat(condenser.condense("那运费谁出", HISTORY)).isNull();
    }

    /**
     * 改跑题了：小模型有时把新话题改回旧话题（实测「密码输错几次会被锁」→「七天无理由退货要什么条件」），或者换掉了用户问的东西——原话里的字
     * 大多不在结果里，当作没改写；正常的换说法（「运费由谁承担」）不受影响。
     */
    @Test
    void offTopicRewriteIsRejected() {
        FakeChatModel model = new FakeChatModel();
        QuestionCondenser condenser = condenser(model, MemoryFixtures.properties());

        model.willCondense("七天无理由退货要什么条件");
        assertThat(condenser.condense("密码输错几次会被锁", HISTORY)).isNull();
        model.willCondense("发票什么时候能开");
        assertThat(condenser.condense("那专票呢", HISTORY)).isNull();

        assertThat(QuestionCondenser.plausible("那运费谁出", "七天无理由退货的运费由谁承担")).isTrue();
        assertThat(QuestionCondenser.plausible("它什么时候下的单", "订单 21182516707889029120785 是什么时候下的单"))
                .isTrue();
        assertThat(QuestionCondenser.plausible("那呢", "好的"))
                .as("原话只有语气词、代词时不做要求")
                .isTrue();
    }

    /**
     * 模型挂了：往外抛 ModelCallException（审查修复）。原来是悄悄退回原话，结果 ChatService 紧接着拿原话去识别意图，同一个挂掉的模型再等一轮超时；
     * 现在由 ChatService 接住，跳过意图识别，直接降级或报错。
     */
    @Test
    void modelFailureIsThrownNotSwallowed() {
        FakeChatModel model = new FakeChatModel();
        model.willFailCondense(new ResourceAccessException("连不上", new ConnectException("Connection refused")));

        assertThatThrownBy(() -> condenser(model, MemoryFixtures.properties()).condense("那运费谁出", HISTORY))
                .isInstanceOfSatisfying(
                        ModelCallException.class,
                        e -> assertThat(e.failure()).isEqualTo(ModelFailure.MODEL_UNAVAILABLE));
    }

    /** 历史只给最近几轮，长回答截短。 */
    @Test
    void transcriptKeepsRecentTurnsAndTruncates() {
        List<Message> history = List.of(
                MemoryMessages.user("第一问", AT),
                MemoryMessages.assistant("第一答", AT),
                MemoryMessages.user("第二问", AT),
                MemoryMessages.assistant("很长".repeat(200), AT));

        String transcript = QuestionCondenser.transcript(history, 1);

        assertThat(transcript).doesNotContain("第一问").startsWith("用户：第二问\n助手：");
        assertThat(transcript.length()).isLessThan(250);
        assertThat(transcript).endsWith("…");
    }
}
