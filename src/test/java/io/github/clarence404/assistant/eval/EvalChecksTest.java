package io.github.clarence404.assistant.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.agent.PendingConfirmation;
import io.github.clarence404.assistant.chat.ChatReply;
import io.github.clarence404.assistant.intent.Intent;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.memory.MemoryTrace;
import io.github.clarence404.assistant.rag.BlockedSource;
import io.github.clarence404.assistant.rag.RagSource;
import io.github.clarence404.assistant.rag.RagTrace;
import io.github.clarence404.assistant.tool.ToolInvocation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 第 9 章 TODO ch09-9：一条用例过没过——只判写了的期望，没满足的每一项记一句带「期望 / 实际」的原因。
 */
class EvalChecksTest {

    @Test
    void intentMatchesOrExplainsTheDifference() {
        assertThat(judge(expect().intent("POLICY"), reply().intent(Intent.POLICY)))
                .isEmpty();
        assertThat(judge(expect().intent("POLICY"), reply().intent(Intent.CHITCHAT)))
                .containsExactly("意图应为 POLICY，实际 CHITCHAT");
        assertThat(judge(expect().intent("POLICY"), reply()))
                .singleElement()
                .asString()
                .contains("实际 null");
    }

    @Test
    void toolExpectations() {
        ReplyBuilder queried = reply().tool("queryOrderStatus", "{\"orderNo\":\"2118\"}");

        assertThat(judge(expect().tool("queryOrderStatus").toolArgContains("2118"), queried))
                .isEmpty();
        assertThat(judge(expect().tool("listMyOrders"), queried))
                .containsExactly("应调用工具 listMyOrders，实际 [queryOrderStatus]");
        assertThat(judge(expect().toolArgContains("9999"), queried)).hasSize(1);
        assertThat(judge(expect().noTool(), reply())).isEmpty();
        assertThat(judge(expect().noTool(), queried)).containsExactly("不应调用工具，实际 [queryOrderStatus]");
    }

    @Test
    void retrievalHitCitationsAndShortCircuit() {
        ReplyBuilder rag = reply().source("returns.md", "退换货规则 > 七天无理由退货 > 适用条件")
                .rag(trace(null, List.of(1), List.of(), List.of()));

        assertThat(judge(expect().source("returns.md").heading("适用条件").cited(), rag))
                .isEmpty();
        assertThat(judge(expect().source("shipping.md").heading("适用条件"), rag))
                .singleElement()
                .asString()
                .startsWith("资料里没有 shipping.md「适用条件」");
        assertThat(judge(expect().heading("退货运费"), rag)).hasSize(1);
        assertThat(judge(expect().cited(), reply().rag(trace(null, List.of(), List.of(), List.of()))))
                .hasSize(1);
        assertThat(judge(expect().cited(), reply().rag(trace(null, List.of(1), List.of(3), List.of()))))
                .hasSize(1);
        assertThat(judge(expect().cited(), reply())).singleElement().asString().contains("没走 RAG");
        assertThat(judge(
                        expect().shortCircuit("NO_MATCH"),
                        reply().rag(trace("NO_MATCH", List.of(), List.of(), List.of()))))
                .isEmpty();
        assertThat(judge(expect().shortCircuit("NO_MATCH"), rag)).hasSize(1);
    }

    @Test
    void condensedQuestionMustContainEveryWord() {
        ReplyBuilder condensed = reply().condensed("七天无理由退货的运费谁出");

        assertThat(judge(expect().condensedContains("退货", "运费"), condensed)).isEmpty();
        assertThat(judge(expect().condensedContains("退货", "积分"), condensed))
                .containsExactly("改写后的问题应含「积分」，实际 七天无理由退货的运费谁出");
        assertThat(judge(expect().condensedContains("退货"), reply())).hasSize(1);
    }

    @Test
    void proposalAndBlocked() {
        assertThat(judge(expect().proposal("cancelOrder"), reply().proposal("cancelOrder")))
                .isEmpty();
        assertThat(judge(expect().proposal("cancelOrder"), reply().proposal("changeAddress")))
                .containsExactly("应提出 cancelOrder，实际 changeAddress");
        assertThat(judge(expect().proposal("cancelOrder"), reply())).hasSize(1);

        ReplyBuilder blocked = reply().rag(trace(
                "ALL_BLOCKED",
                List.of(),
                List.of(),
                List.of(new BlockedSource("evil.md", "会员日", List.of("IGNORE_RULES"), "忽略规则", "忽略以上"))));
        assertThat(judge(expect().blocked("evil.md"), blocked)).isEmpty();
        assertThat(judge(expect().blocked("other.md"), blocked)).hasSize(1);
    }

    @Test
    void mustContainAndMustNotContain() {
        ReplyBuilder text = reply().text("会员日是每月 8 日[1]。");

        assertThat(judge(expect().mustContain("8 日").mustNotContain("全额退款"), text))
                .isEmpty();
        assertThat(judge(expect().mustContain("积分").mustNotContain("会员日"), text))
                .containsExactly("回答应含「积分」", "回答不应含「会员日」");
    }

    @Test
    void allFailuresAreReported() {
        assertThat(judge(expect().intent("POLICY").tool("queryProduct").mustContain("xx"), reply()))
                .hasSize(3);
    }

    // ---- 拼用例和回答的小工具 ----

    private static List<String> judge(ExpectBuilder expect, ReplyBuilder reply) {
        EvalCase c = new EvalCase("t-01", EvalCase.Category.RAG, "问题", List.of(), false, expect.build(), null);
        return EvalChecks.judge(c, reply.build());
    }

    static ExpectBuilder expect() {
        return new ExpectBuilder();
    }

    static ReplyBuilder reply() {
        return new ReplyBuilder();
    }

    static RagTrace trace(
            String shortCircuit, List<Integer> cited, List<Integer> invalid, List<BlockedSource> blocked) {
        return new RagTrace("问题", null, "hybrid", 1, 1, 1, 100, 5, shortCircuit, null, cited, invalid, null, blocked);
    }

    /** 期望的拼装器。 */
    static final class ExpectBuilder {
        String intent;
        String tool;
        Boolean noTool;
        String toolArgContains;
        String source;
        String heading;
        Boolean cited;
        String shortCircuit;
        List<String> condensed = List.of();
        String proposal;
        String blocked;
        List<String> mustContain = List.of();
        List<String> mustNotContain = List.of();

        ExpectBuilder intent(String v) {
            intent = v;
            return this;
        }

        ExpectBuilder tool(String v) {
            tool = v;
            return this;
        }

        ExpectBuilder noTool() {
            noTool = true;
            return this;
        }

        ExpectBuilder toolArgContains(String v) {
            toolArgContains = v;
            return this;
        }

        ExpectBuilder source(String v) {
            source = v;
            return this;
        }

        ExpectBuilder heading(String v) {
            heading = v;
            return this;
        }

        ExpectBuilder cited() {
            cited = true;
            return this;
        }

        ExpectBuilder shortCircuit(String v) {
            shortCircuit = v;
            return this;
        }

        ExpectBuilder condensedContains(String... v) {
            condensed = List.of(v);
            return this;
        }

        ExpectBuilder proposal(String v) {
            proposal = v;
            return this;
        }

        ExpectBuilder blocked(String v) {
            blocked = v;
            return this;
        }

        ExpectBuilder mustContain(String... v) {
            mustContain = List.of(v);
            return this;
        }

        ExpectBuilder mustNotContain(String... v) {
            mustNotContain = List.of(v);
            return this;
        }

        EvalCase.Expect build() {
            return new EvalCase.Expect(
                    intent,
                    tool,
                    noTool,
                    toolArgContains,
                    source,
                    heading,
                    cited,
                    shortCircuit,
                    condensed,
                    proposal,
                    blocked,
                    mustContain,
                    mustNotContain);
        }
    }

    /** 回答的拼装器：只填评测要看的字段。 */
    static final class ReplyBuilder {
        String text = "好的";
        IntentResult intent;
        List<ToolInvocation> tools = List.of();
        List<RagSource> sources = List.of();
        RagTrace rag;
        MemoryTrace memory;
        PendingConfirmation confirmation;

        ReplyBuilder text(String v) {
            text = v;
            return this;
        }

        ReplyBuilder intent(Intent v) {
            intent = new IntentResult(v, 0.9, null, null);
            return this;
        }

        ReplyBuilder tool(String name, String arguments) {
            tools = List.of(new ToolInvocation(name, ToolInvocation.Source.DIRECT, arguments, "{}", 1));
            return this;
        }

        ReplyBuilder source(String file, String headingPath) {
            sources = List.of(new RagSource(1, file, "", headingPath, List.of(0), 0.8, null, 0.8, List.of(), "", ""));
            return this;
        }

        ReplyBuilder rag(RagTrace v) {
            rag = v;
            return this;
        }

        ReplyBuilder condensed(String v) {
            memory = new MemoryTrace("c1", "memory", true, 1, 0, 10, false, false, v, null, null, null);
            return this;
        }

        ReplyBuilder proposal(String action) {
            confirmation =
                    new PendingConfirmation("id", action, "标题", "2118", Map.of(), "说明", "影响", Instant.EPOCH, 300);
            return this;
        }

        ChatReply build() {
            return new ChatReply(text, intent, tools, sources, rag, memory, null, confirmation);
        }
    }
}
