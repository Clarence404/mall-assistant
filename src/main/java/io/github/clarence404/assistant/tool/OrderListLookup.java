package io.github.clarence404.assistant.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 工具 {@code listMyOrders} 的返回值：当前登录用户最近的几笔订单，转成 JSON 交回给模型。第 3 章补充。
 *
 * <p>所在层：工具。由 {@link MallTools#listMyOrders} 生成；上游是模型（原生工具调用）或 {@code ChatService} 的「按意图直接查询」
 * （查订单却没给订单号时）。
 *
 * <p>和 {@link OrderLookup} 一样，查不到也返回结构化结果：没登录、令牌过期、商城挂了都是 {@code found=false} + 原因。
 * 有一点不同：<b>一笔订单都没有</b>算「查到了」（{@code found=true}、{@code orders} 为空），因为这本身就是一个确定的答案
 * （「您还没有订单」），和「没查成」要区分开，否则模型可能回答「系统出错了，请稍后再试」。
 *
 * <p>{@link #note} 是写给模型的一句「下一步怎么做」：列表是在用户没给订单号时查的，用户真正想问的多半是其中某一笔，
 * 所以提示模型把订单列出来、再问用户要看哪一笔。把这句话放在工具结果里（而不是系统提示词里），只有真的查了列表时才出现，
 * 系统提示词保持短（第 3 章实测：系统提示词越长，小模型越不愿意调工具）。
 *
 * @param found 是否查成（空列表也算查成）
 * @param reason 没查成的原因，直接可以说给用户听；查成时为 null
 * @param orders 最近的订单，按下单时间从新到旧；没查成时为 null
 * @param hasMore 是否还有更早的订单没列出来；没查成时为 null
 * @param note 给模型的下一步提示；没查成时为 null
 * @param logistics 物流说明，同 {@link OrderLookup#NO_LOGISTICS}：列表里同样没有物流轨迹，明确写出来，防止模型编造
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderListLookup(
        boolean found, String reason, List<Brief> orders, Boolean hasMore, String note, String logistics) {

    /** 有订单时给模型的提示：逐条列出，再问用户要看哪一笔。 */
    public static final String LIST_NOTE = "把这些订单逐条告诉用户（订单号、状态、金额、商品），再问用户想看哪一笔";

    /** 没有订单时给模型的提示。 */
    public static final String EMPTY_NOTE = "这个账号还没有订单，如实告诉用户";

    /**
     * 列表里的一笔订单：比 {@link OrderLookup} 少了物流说明（整个列表共用一条 {@link OrderListLookup#logistics}）。
     *
     * @param orderNo 订单号
     * @param status 中文状态与说明，如「待发货（已付款，商家还没发货）」
     * @param payAmount 实付金额（元）
     * @param items 商品摘要，如「青柚 · 红心柚 2 只装 ×1」；补查商品失败时为 null（不影响列出订单）
     * @param createdAt 下单时间，如 2026-11-05 15:58
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Brief(String orderNo, String status, String payAmount, String items, String createdAt) {}

    /**
     * 查成了。
     *
     * @param orders 订单，可以为空列表
     * @param hasMore 是否还有更早的订单
     * @return found=true 的结果，按有没有订单带上不同的 {@link #note}
     */
    public static OrderListLookup of(List<Brief> orders, boolean hasMore) {
        return new OrderListLookup(
                true, null, orders, hasMore, orders.isEmpty() ? EMPTY_NOTE : LIST_NOTE, OrderLookup.NO_LOGISTICS);
    }

    /**
     * 没查成。
     *
     * @param reason 原因，直接可以说给用户听
     * @return found=false 的结果
     */
    public static OrderListLookup notFound(String reason) {
        return new OrderListLookup(false, reason, null, null, null, null);
    }
}
