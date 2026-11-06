package io.github.clarence404.assistant.intent;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * 用户消息的意图。name() 就是模型要输出、接口要返回的值。
 *
 * <p>所在层：业务层的领域模型（第 2 章引入）。作为 {@link IntentResult#intent()} 的类型写进 JSON Schema（枚举值就是 Schema 里的
 * {@code enum}，模型只能从中选），{@code prompts/intent-v1.st} 里也逐个解释了每个值；{@code ChatService} 据此决定是否拒答。
 * 加减取值时，提示词里的说明和示例要一起改。
 */
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
     *
     * <p>{@code @JsonCreator} 让 Jackson 反序列化时走这个方法，而不是默认的「必须与 name() 完全一致，否则抛异常」。
     * 模型的输出没有百分之百的保证，尤其退回到「格式说明拼进提示词」的方式时，可能写成小写或带连字符；宽松一点能少丢一些本来能用的结果。
     *
     * @param value 模型输出的 intent 字段，可能为 null
     * @return 对应的枚举值，认不出时为 {@link #UNKNOWN}，从不返回 null
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
