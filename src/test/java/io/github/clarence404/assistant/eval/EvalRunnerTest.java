package io.github.clarence404.assistant.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.chat.ChatReply;
import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import io.github.clarence404.assistant.intent.Intent;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.ops.ModelCall;
import io.github.clarence404.assistant.ops.UsageTrace;
import io.github.clarence404.assistant.rag.RagSource;
import io.github.clarence404.assistant.rag.RagTrace;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 评测运行器本身（第 9 章）：用一个按剧本回答的假被测对象，验证多轮先问前几轮、同一个会话 ID、Token 把所有轮次加起来、出错记为失败不打断、
 * 裁判只给 RAG 回答打分。判定逻辑来自 {@link EvalChecks}（ch09-9），汇总来自 {@link EvalReport#summarize}（ch09-10）。
 */
class EvalRunnerTest {

    private static final EvalSet SET = EvalSet.parse("test.jsonl", """
            {"id":"intent-01","category":"intent","message":"积分多久过期","expect":{"intent":"POLICY"}}
            {"id":"multi-01","category":"rewrite","history":["七天无理由退货要什么条件"],"message":"那运费谁出","login":true,"expect":{"mustContain":["运费"]}}
            {"id":"rag-01","category":"rag","message":"积分能抵多少钱","expect":{"source":"points-membership.md","cited":true}}
            {"id":"broken-01","category":"tool","message":"挂了","expect":{"tool":"queryOrderStatus"}}
            """);

    @Test
    void runsEveryCaseAndSummarizes() {
        List<String> asked = new ArrayList<>();
        EvalTarget target = (message, conversationId, login) -> {
            asked.add(message + "|" + (conversationId != null) + "|" + login);
            return switch (message) {
                case "积分多久过期" -> reply("积分有效期 12 个月。", Intent.POLICY, 100, 20, null);
                case "七天无理由退货要什么条件" -> reply("签收后 7 天内。", Intent.POLICY, 300, 40, null);
                case "那运费谁出" -> reply("退货运费由买家承担。", Intent.POLICY, 400, 50, null);
                case "积分能抵多少钱" -> reply("100 积分抵 1 元[1]。", Intent.POLICY, 500, 30, rag());
                default -> throw new ModelCallException(ModelFailure.MODEL_TIMEOUT, null);
            };
        };

        EvalReport report = new EvalRunner(target).run(SET, "fake");

        assertThat(asked)
                .containsExactly(
                        "积分多久过期|false|false",
                        "七天无理由退货要什么条件|true|true",
                        "那运费谁出|true|true",
                        "积分能抵多少钱|false|false",
                        "挂了|false|false");
        assertThat(report.model()).isEqualTo("fake");
        assertThat(report.evalSetVersion()).isEqualTo(SET.version());
        assertThat(report.results()).extracting(EvalResult::passed).containsExactly(true, true, true, false);
        EvalResult multi = report.results().get(1);
        assertThat(multi.inputTokens()).as("多轮把前几轮也算上").isEqualTo(700);
        assertThat(multi.outputTokens()).isEqualTo(90);
        assertThat(report.results().get(3).failures())
                .singleElement()
                .asString()
                .startsWith("调用失败：MODEL_TIMEOUT");
        assertThat(report.summary().passRate()).isEqualTo(0.75);
        assertThat(report.summary().categories()).containsKeys("INTENT", "TOOL", "RAG", "REWRITE");
    }

    @Test
    void conversationIdIsSharedWithinACaseAndFreshAcrossCases() {
        List<String> ids = new ArrayList<>();
        EvalSet set = EvalSet.parse("two.jsonl", """
                {"id":"a","category":"rewrite","history":["一"],"message":"二","expect":{"mustContain":["好"]}}
                {"id":"b","category":"rewrite","history":["三"],"message":"四","expect":{"mustContain":["好"]}}
                """);

        new EvalRunner((message, conversationId, login) -> {
                    ids.add(conversationId);
                    return reply("好", Intent.CHITCHAT, 1, 1, null);
                })
                .run(set, "fake");

        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(ids.get(2)).isEqualTo(ids.get(3)).isNotEqualTo(ids.get(0));
    }

    @Test
    void judgeScoresOnlyRagAnswersWithSources() {
        List<String> judged = new ArrayList<>();
        EvalJudge judge = (question, sources, answer) -> {
            judged.add(question);
            return new EvalJudge.Verdict(4, "基本忠实");
        };
        EvalTarget target = (message, conversationId, login) -> message.equals("积分能抵多少钱")
                ? reply("100 积分抵 1 元[1]。", Intent.POLICY, 1, 1, rag())
                : reply("好", Intent.POLICY, 1, 1, null);

        EvalReport report = new EvalRunner(target, judge).run(SET, "fake");

        assertThat(judged).containsExactly("积分能抵多少钱");
        assertThat(report.results().get(2).judgeScore()).isEqualTo(4);
        assertThat(report.results().get(2).judgeReason()).isEqualTo("基本忠实");
        assertThat(report.summary().judgeAverage()).isEqualTo(4.0);
        assertThat(report.summary().judged()).isEqualTo(1);
    }

    private static ChatReply reply(String text, Intent intent, int in, int out, RagTrace rag) {
        List<RagSource> sources = rag == null
                ? List.of()
                : List.of(new RagSource(
                        1,
                        "points-membership.md",
                        "积分与会员",
                        "积分与会员 > 积分使用",
                        List.of(0),
                        0.8,
                        null,
                        0.8,
                        List.of("vector"),
                        "",
                        "积分与会员 > 积分使用\n100 积分抵 1 元。"));
        return new ChatReply(
                text,
                new IntentResult(intent, 0.9, null, null),
                List.of(),
                sources,
                rag,
                null,
                null,
                null,
                UsageTrace.of(List.of(new ModelCall("answer", null, in, out, false, 1))));
    }

    private static RagTrace rag() {
        return new RagTrace("q", null, "hybrid", 1, 1, 1, 10, 1, null, null, List.of(1), List.of(), false);
    }
}
