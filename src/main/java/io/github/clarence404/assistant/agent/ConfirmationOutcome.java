package io.github.clarence404.assistant.agent;

import java.time.Instant;

/**
 * 用户点了确认或取消之后的结果（第 7 章引入）。
 *
 * <p>所在层：接口层的数据。{@code POST /api/agent/confirmations/{id}} 返回它；页面把 {@link #message} 显示成一条助手消息，卡片变成「已执行 / 已取消 /
 * 执行失败」。<b>幂等</b>：同一个确认 ID 再点一次同样的按钮，返回的是同一个结果（同一个对象），商城接口不会被调第二次。
 *
 * @param id 确认 ID
 * @param decision 用户的决定
 * @param status 结果
 * @param message 给用户看的结果说明（代码写的固定话术，不经过模型），也会作为一轮对话写回会话记忆
 * @param afterSalesId 提交售后申请成功时的申请单号；其余为 null
 * @param decidedAt 决定的时间
 */
public record ConfirmationOutcome(
        String id, Decision decision, Status status, String message, String afterSalesId, Instant decidedAt) {

    /** 用户的决定。 */
    public enum Decision {
        /** 点了「确认」。 */
        APPROVE,
        /** 点了「取消」。 */
        REJECT
    }

    /** 结果。 */
    public enum Status {
        /** 执行成功。 */
        EXECUTED,
        /** 执行了但商城拒绝或不可用（如订单已经付款不能取消）：没办成，结果说明里写着原因。 */
        FAILED,
        /** 用户拒绝，什么都没做。 */
        REJECTED
    }
}
