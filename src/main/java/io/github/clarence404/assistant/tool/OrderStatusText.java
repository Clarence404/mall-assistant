package io.github.clarence404.assistant.tool;

import java.util.Arrays;
import java.util.List;

/**
 * 商城订单状态 → 给用户看的中文。
 *
 * <p>所在层：工具（第 3 章引入）。{@link MallTools#queryOrderStatus} 用它把商城返回的枚举名翻成中文再交给模型。
 *
 * <p>为什么不让模型自己翻译：工具结果给得越「直接可用」，模型越少发挥，小模型尤其如此。把 {@code PENDING_SHIPMENT}
 * 交给 0.5b 的模型，它可能说成「待付款」；直接给「待发货（已付款，商家还没发货）」，它照抄就对了。
 * 不认识的状态按商城前端的约定显示「处理中」，不报错。
 */
public enum OrderStatusText {
    PENDING_PAYMENT("待支付", "还没付款，超时未付会自动取消"),
    PENDING_SHIPMENT("待发货", "已付款，商家还没发货"),
    SHIPPED("已发货", "商家已发货"),
    COMPLETED("已完成", "已确认收货"),
    CANCELLED("已取消", "订单已取消"),
    CLOSED("已关闭", "订单已关闭");

    private final String text;
    private final String explanation;

    OrderStatusText(String text, String explanation) {
        this.text = text;
        this.explanation = explanation;
    }

    /**
     * 商城的状态枚举名 → 「中文状态（说明）」。
     *
     * @param status 商城返回的状态，如 PENDING_SHIPMENT；null 或不认识时返回「处理中」
     * @return 如「待发货（已付款，商家还没发货）」
     */
    public static String describe(String status) {
        return Arrays.stream(values())
                .filter(s -> s.name().equals(status))
                .findFirst()
                .map(s -> s.text + "（" + s.explanation + "）")
                .orElse("处理中");
    }

    /** 全部中文状态词，供 {@link ToolResultGuard} 检查回答里有没有凭空出现的状态。 */
    public static List<String> words() {
        return Arrays.stream(values()).map(s -> s.text).toList();
    }
}
