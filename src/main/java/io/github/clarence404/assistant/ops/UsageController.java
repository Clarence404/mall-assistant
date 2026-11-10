package io.github.clarence404.assistant.ops;

import io.github.clarence404.assistant.guard.CallerResolver;
import io.github.clarence404.assistant.mall.MallToken;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询接口 {@code GET /api/usage/me}：我今天用了多少 Token、预算还剩多少（第 9 章引入）。
 *
 * <p>所在层：接口层。和对话接口一样按 {@link CallerResolver} 认人（登录按商城用户，匿名按 IP），只能看自己的；所有人的汇总在
 * {@code /actuator/tokens}（{@link TokenUsageEndpoint}）。页面调试面板「Token」一栏打开时调它。
 */
@RestController
public class UsageController {

    private final UsageLedger ledger;
    private final CallerResolver callers;

    /**
     * 创建。
     *
     * @param ledger Token 总账
     * @param callers 认人
     */
    public UsageController(UsageLedger ledger, CallerResolver callers) {
        this.ledger = ledger;
        this.callers = callers;
    }

    /**
     * 我今天的用量。
     *
     * @param authorization {@code Bearer <商城令牌>}，没登录时不带
     * @param request 取来源地址
     * @return 用量
     */
    @GetMapping("/api/usage/me")
    public MyUsage me(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        String caller = callers.resolve(MallToken.fromAuthorizationHeader(authorization), request.getRemoteAddr());
        long used = ledger.usedToday(caller);
        long budget = ledger.dailyBudget();
        return new MyUsage(caller, used, budget, budget > 0 ? Math.max(0, budget - used) : null);
    }

    /**
     * 我的用量。
     *
     * @param caller 算在谁头上（{@code user:…} / {@code ip:…}）
     * @param todayTokens 今天用了多少
     * @param dailyBudget 每日预算，0 = 不限
     * @param remaining 还剩多少；不限时为 null
     */
    public record MyUsage(String caller, long todayTokens, long dailyBudget, Long remaining) {}
}
