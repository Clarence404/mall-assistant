package io.github.clarence404.assistant.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.rag.RagSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * LLM 裁判（第 9 章，给好的代码）：提示词里有资料、问题和回答；裁判先分析、最后一行给 JSON，代码取最后一个合格的结论。
 */
class ChatClientJudgeTest {

    private static final List<RagSource> SOURCES = List.of(new RagSource(
            1,
            "points-membership.md",
            "积分与会员",
            "积分与会员 > 积分有效期",
            List.of(0),
            0.8,
            null,
            0.8,
            List.of("vector"),
            "",
            "积分与会员 > 积分有效期\n积分有效期为获得之日起的 12 个月。"));

    @Test
    void sendsSourcesQuestionAndAnswerAndParsesTheLastVerdict() {
        FakeChatModel model = new FakeChatModel();
        model.willReply("""
                依据：回答说 12 个月，资料 [1] 写的是 12 个月。
                {"score": 2, "reason": "草稿"}
                复核后：
                {"score": 5, "reason": "依据在 [1]"}""");

        EvalJudge.Verdict verdict =
                new ChatClientJudge(ChatClient.builder(model).build()).judge("积分多久过期", SOURCES, "积分有效期 12 个月[1]。");

        assertThat(verdict).isEqualTo(new EvalJudge.Verdict(5, "依据在 [1]"));
        String prompt = FakeChatModel.allText(model.lastPrompt());
        assertThat(prompt)
                .contains("[1] 积分与会员 > 积分有效期")
                .contains("<用户问题>积分多久过期</用户问题>")
                .contains("积分有效期 12 个月[1]。");
    }

    @Test
    void invalidOrMissingVerdictIsNull() {
        assertThat(ChatClientJudge.parse("## 分析\n回答基本忠实。")).isNull();
        assertThat(ChatClientJudge.parse("{\"score\": 9, \"reason\": \"超出范围\"}"))
                .isNull();
        assertThat(ChatClientJudge.parse("{\"score\": \"好\"}")).isNull();
        assertThat(ChatClientJudge.parse(null)).isNull();
    }

    @Test
    void modelFailureIsNotFatal() {
        FakeChatModel model = new FakeChatModel();
        model.willFail(new IllegalStateException("裁判挂了"));

        assertThat(new ChatClientJudge(ChatClient.builder(model).build()).judge("q", SOURCES, "a"))
                .isNull();
    }
}
