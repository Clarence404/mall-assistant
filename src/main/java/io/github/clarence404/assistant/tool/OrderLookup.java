package io.github.clarence404.assistant.tool;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 工具 {@code queryOrderStatus} 的返回值：Spring AI 把它转成 JSON，作为「工具结果」消息交回给模型。
 *
 * <p>所在层：工具（第 3 章引入）。
 *
 * <p>为什么查不到也返回一个对象（{@code found=false} + {@code reason}），而不是抛异常：工具结果是给<b>模型</b>看的。
 * 抛异常的话，框架要么把异常信息（可能带内部地址、堆栈）原样交给模型，要么整个对话失败；返回结构化的「没查到，原因是……」，
 * 模型就能照实告诉用户「没有找到这笔订单」或「请先登录」，而不是自己编一个状态出来。
 * {@code null} 字段不输出（{@link JsonInclude}），结果越短，占用的 token 越少，模型也越不容易被无关字段带偏。
 *
 * @param found 是否查到
 * @param reason 没查到的原因，直接可以说给用户听；查到时为 null
 * @param orderNo 订单号
 * @param status 中文状态与说明，如「待发货（已付款，商家还没发货）」
 * @param payAmount 实付金额（元）
 * @param items 商品摘要，如「青柚 · 红心柚 2 只装 ×1」
 * @param createdAt 下单时间，如 2026-11-05 15:58
 * @param logistics 物流说明：商城没有提供物流轨迹接口，这里明确写出来，防止模型编造快递单号和到货时间
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderLookup(
        boolean found,
        String reason,
        String orderNo,
        String status,
        String payAmount,
        String items,
        String createdAt,
        String logistics) {

    /** 物流说明：本系统查不到物流轨迹。 */
    public static final String NO_LOGISTICS = "系统暂不提供物流轨迹，快递公司、单号和到货时间请在「我的订单」里查看";

    /**
     * 没查到。
     *
     * @param orderNo 用户给的订单号（可能为 null）
     * @param reason 原因，直接可以说给用户听
     * @return found=false 的结果
     */
    public static OrderLookup notFound(String orderNo, String reason) {
        return new OrderLookup(false, reason, orderNo, null, null, null, null, null);
    }
}
