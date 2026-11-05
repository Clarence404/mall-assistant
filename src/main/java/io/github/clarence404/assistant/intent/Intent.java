package io.github.clarence404.assistant.intent;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/** 用户消息的意图。name() 就是模型要输出、接口要返回的值。 */
public enum Intent {
    /** 查订单状态、物流。 */
    ORDER_STATUS,
    /** 问商品价格、库存、规格。 */
    PRODUCT_INFO,
    /** 退换货、退款、投诉。 */
    AFTER_SALES,
    /** 打招呼、感谢、闲聊。 */
    CHITCHAT,
    /** 与购物无关：直接拒答，不调回答模型。 */
    OUT_OF_SCOPE,
    /** 兜底：看不出意图，或者模型输出无法解析。 */
    UNKNOWN;

    /**
     * 宽松解析模型给的值：忽略大小写、空格与连字符（{@code "order-status"}、{@code " Order_Status "} 都认），认不出的一律
     * {@link #UNKNOWN}，不因为一个标签写错就整条解析失败。
     */
    @JsonCreator
    public static Intent from(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String normalized =
                value.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (Intent intent : values()) {
            if (intent.name().equals(normalized)) {
                return intent;
            }
        }
        return UNKNOWN;
    }
}
