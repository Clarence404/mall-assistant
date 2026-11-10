package io.github.clarence404.assistant.eval;

import io.github.clarence404.assistant.chat.ChatReply;
import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.ops.UsageTrace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 评测运行器：把一份评测集逐条交给 {@link EvalTarget}，判定、计时、数 Token，汇总成 {@link EvalReport}（第 9 章引入）。
 *
 * <p>所在层：评测。调用方是测试：{@code EvalRunnerTest}（假的被测对象）、{@code EvalGateTest}（CI 里的评测门禁，回放录制的真实模型输出）、
 * {@code EvalIT}（设了环境变量时对真实 Ollama 跑，出报告、可选录制与更新基线）。
 *
 * <p>一条用例怎么跑：有 {@code history} 的先按顺序问前几轮（同一个会话 ID，不判定），再问 {@code message}——只有这一句被判定、被计时；
 * Token 把所有轮次都算上（多轮的代价就在这里）。抛异常（模型挂了、超时）记为不通过，原因是错误码，不让一条用例的失败打断整次评测。
 * 用例之间互不影响：每条多轮用例用一个新的会话 ID。
 */
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final EvalTarget target;
    private final EvalJudge judge;

    /**
     * 不请裁判。
     *
     * @param target 被测对象
     */
    public EvalRunner(EvalTarget target) {
        this(target, null);
    }

    /**
     * 请一个 LLM 裁判给 RAG 回答打分（只进报告，不影响通过与否）。
     *
     * @param target 被测对象
     * @param judge 裁判，可以为 null
     */
    public EvalRunner(EvalTarget target, EvalJudge judge) {
        this.target = target;
        this.judge = judge;
    }

    /**
     * 跑一遍。
     *
     * @param set 评测集
     * @param model 被测的模型（写进报告，如 {@code qwen2.5:1.5b}、{@code replay:qwen2.5:1.5b}）
     * @return 报告
     */
    public EvalReport run(EvalSet set, String model) {
        String startedAt = Instant.now().toString();
        List<EvalResult> results = new ArrayList<>();
        for (EvalCase c : set.cases()) {
            EvalResult result = runCase(c);
            log.info(
                    "[{}] {} {} ms {}",
                    c.id(),
                    result.passed() ? "通过" : "不通过",
                    result.durationMs(),
                    result.passed() ? "" : result.failures());
            results.add(result);
        }
        return new EvalReport(model, set.name(), set.version(), startedAt, EvalReport.summarize(results), results);
    }

    /**
     * 跑一条用例。
     *
     * @param c 用例
     * @return 结果
     */
    EvalResult runCase(EvalCase c) {
        String conversationId = c.history().isEmpty() ? null : UUID.randomUUID().toString();
        long input = 0;
        long output = 0;
        boolean estimated = false;
        long durationMs = 0;
        List<String> failures;
        Integer score = null;
        String reason = null;
        try {
            for (String earlier : c.history()) {
                UsageTrace usage =
                        target.ask(earlier, conversationId, c.login()).usage();
                if (usage != null) {
                    input += usage.inputTokens();
                    output += usage.outputTokens();
                    estimated |= usage.estimated();
                }
            }
            long start = System.nanoTime();
            ChatReply reply = target.ask(c.message(), conversationId, c.login());
            durationMs = (System.nanoTime() - start) / 1_000_000;
            if (reply.usage() != null) {
                input += reply.usage().inputTokens();
                output += reply.usage().outputTokens();
                estimated |= reply.usage().estimated();
            }
            failures = EvalChecks.judge(c, reply);
            if (judge != null && shouldJudge(c, reply)) {
                EvalJudge.Verdict verdict = judge.judge(c.message(), reply.sources(), reply.reply());
                if (verdict != null) {
                    score = verdict.score();
                    reason = verdict.reason();
                }
            }
        } catch (RuntimeException e) {
            failures = List.of("调用失败：" + ModelCallException.from(e).failure().name() + "（" + e.getMessage() + "）");
        }
        return new EvalResult(
                c.id(),
                c.category(),
                failures.isEmpty(),
                failures,
                durationMs,
                input,
                output,
                estimated,
                score,
                reason);
    }

    /** 只给「带着资料调了模型」的规则类回答打分：短路、降级的回答是代码写的，没什么可评的。 */
    private static boolean shouldJudge(EvalCase c, ChatReply reply) {
        return (c.category() == EvalCase.Category.RAG || c.category() == EvalCase.Category.INJECTION)
                && reply.rag() != null
                && reply.rag().shortCircuit() == null
                && reply.sources() != null
                && !reply.sources().isEmpty();
    }
}
