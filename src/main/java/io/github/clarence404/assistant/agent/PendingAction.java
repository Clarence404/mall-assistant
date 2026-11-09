package io.github.clarence404.assistant.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个还没执行的写操作：做什么、对哪笔订单、参数、给用户看的说明和影响（第 7 章引入）。
 *
 * <p>所在层：Agent 的领域模型。{@link WriteActionGuard} 校验通过后生成，{@link ConfirmationService#create} 给它配上确认 ID 和过期时间，
 * 用户点确认后 {@link ConfirmationService} 按这里的参数执行。
 *
 * <p><b>参数在生成时就定死了</b>（不可修改的 record + 不可修改的 Map）：确认接口只收「同意 / 拒绝」，不收任何参数。用户在卡片上看到的是什么，
 * 执行的就是什么；就算有人篡改确认请求、在请求体里塞一个别的订单号，也不会被用上。这是「确认」有意义的前提——确认的是<b>这一个具体的操作</b>，
 * 而不是「随便做点什么都行」的授权。
 *
 * @param tool 写操作：cancelOrder、changeAddress 或 applyAfterSales
 * @param orderNo 订单号（已核对过：在这次的查询结果里出现过）
 * @param params 其余参数（改地址的 receiver / phone / address，售后的 reason / type），不可修改
 * @param summary 给用户看的一句话说明，如「取消订单 2118…（青柚 · 红心柚 2 只装 ×1，39.90 元）」
 * @param impact 执行后会怎样，如「取消后订单关闭，不能恢复」
 */
public record PendingAction(AgentTool tool, String orderNo, Map<String, String> params, String summary, String impact) {

    /** 规范化：参数不可修改；只能是写操作。 */
    public PendingAction {
        if (tool.kind() != AgentTool.Kind.WRITE) {
            throw new IllegalArgumentException("只有写操作需要确认：" + tool);
        }
        params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
