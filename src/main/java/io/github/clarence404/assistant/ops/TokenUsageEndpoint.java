package io.github.clarence404.assistant.ops;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/**
 * 运维端点 {@code GET /actuator/tokens}：今天的 Token 用量汇总——合计、按意图、用得最多的调用方和会话（第 9 章引入）。
 *
 * <p>所在层：上线运维。读 {@link UsageLedger#summary}。
 *
 * <p>为什么放在 Actuator 下而不是 {@code /api/...}：里面有别人的用户 ID、IP、会话 ID，是给运维看的，不是给用户看的。Actuator 的端点可以统一管
 * 「暴露哪些」（{@code management.endpoints.web.exposure.include}）、「开在哪个端口」（{@code management.server.port}，生产上放到只有内网能访问的端口），
 * 和 {@code /actuator/prometheus} 一起收口。用户自己的用量走 {@code GET /api/usage/me}（{@link UsageController}）。
 */
@Component
@Endpoint(id = "tokens")
public class TokenUsageEndpoint {

    /** 调用方、会话各列前几个。 */
    static final int TOP = 20;

    private final UsageLedger ledger;

    /**
     * 创建。
     *
     * @param ledger Token 总账
     */
    public TokenUsageEndpoint(UsageLedger ledger) {
        this.ledger = ledger;
    }

    /**
     * 今天的汇总。
     *
     * @return 汇总
     */
    @ReadOperation
    public UsageLedger.Summary summary() {
        return ledger.summary(TOP);
    }
}
