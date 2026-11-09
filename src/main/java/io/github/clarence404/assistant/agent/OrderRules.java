package io.github.clarence404.assistant.agent;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 一笔订单在什么状态下能办什么（第 7 章引入）：代码算好，写进查订单的结果（{@code allowedActions}）给模型看，提写操作时再核对一遍。
 *
 * <p>所在层：Agent 的业务规则。上游是 {@link AgentToolbox}（查订单时附上）和 {@link WriteActionGuard}（提写操作时核对）。
 *
 * <p>规则照抄商城 mall-evolution 的实现，外加本项目「售后申请」的约定：
 *
 * <ul>
 *   <li>待支付：能取消（{@code OrderCancelService}：只有待支付能取消）、能改地址（{@code updateAddress} 的 {@code status IN (10, 20)}）
 *   <li>待发货：能改地址；不能取消（已经付款了，商城返回 409），要退钱只能提交售后申请（仅退款）
 *   <li>已发货、已完成：只能提交售后申请（退货退款）
 *   <li>已取消、已关闭：什么都办不了
 * </ul>
 *
 * <p><b>为什么不让模型自己判断</b>：「待发货能不能取消」这种事模型会凭常识猜（很多商城付款后也能取消），而我们的商城不行。规则由代码算好、作为数据交给它，
 * 小模型照着选就行；就算它不照着选，{@link WriteActionGuard} 也会拦下。商城自己最后还会再校验一次（状态不对 409）——三道里任何一道都不能省：
 * 前两道是为了「提前说清原因、不让用户点了确认才发现办不了」，最后一道才是真正的保证。
 */
public final class OrderRules {

    private OrderRules() {}

    /**
     * 这个状态下能办的写操作。
     *
     * @param status 商城的状态枚举名，可以为 null（当作什么都不能办）
     * @return 写操作，按「取消、改地址、售后」的顺序
     */
    public static List<AgentTool> allowed(String status) {
        if (status == null) {
            return List.of();
        }
        return switch (status) {
            case "PENDING_PAYMENT" -> List.of(AgentTool.CANCEL_ORDER, AgentTool.CHANGE_ADDRESS);
            case "PENDING_SHIPMENT" -> List.of(AgentTool.CHANGE_ADDRESS, AgentTool.APPLY_AFTER_SALES);
            case "SHIPPED", "COMPLETED" -> List.of(AgentTool.APPLY_AFTER_SALES);
            default -> List.of();
        };
    }

    /**
     * 能办的写操作的工具名（写进查询结果给模型看）。
     *
     * @param status 状态
     * @return 工具名列表，如 {@code ["cancelOrder","changeAddress"]}
     */
    public static List<String> allowedNames(String status) {
        return allowed(status).stream().map(AgentTool::toolName).toList();
    }

    /**
     * 能办的写操作的中文说明（给模型看的拒绝原因里用）。
     *
     * @param status 状态
     * @return 如「取消订单、修改收货地址」；什么都不能办时为「没有可以办理的操作」
     */
    public static String describe(String status) {
        List<AgentTool> tools = allowed(status);
        return tools.isEmpty()
                ? "没有可以办理的操作"
                : tools.stream().map(t -> t.title() + "（" + t.toolName() + "）").collect(Collectors.joining("、"));
    }
}
