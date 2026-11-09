package io.github.clarence404.assistant.intent;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * 用户消息的意图。name() 就是模型要输出、接口要返回的值。
 *
 * <p>所在层：业务层的领域模型（第 2 章引入）。作为 {@link IntentResult#intent()} 的类型写进 JSON Schema（枚举值就是 Schema 里的
 * {@code enum}，模型只能从中选），{@code prompts/intent-v2.st}（第 5 章起，v1 没有 POLICY）里也逐个解释了每个值；{@code ChatService} 据此决定是否拒答。
 * 加减取值时，提示词里的说明和示例要一起改。
 */
public enum Intent {
    /** 查订单状态、物流。 */
    ORDER_STATUS,
    /** 问商品价格、库存、规格。 */
    PRODUCT_INFO,
    /** 自己的订单要退换货、退款、维修、投诉（「收到的杯子碎了怎么退」）。第 5 章起和 POLICY 一样走 RAG。 */
    AFTER_SALES,
    /**
     * 问商城的规则和政策：退换货条件、运费与发货时效、发票、积分与会员、账户安全（「积分多久过期」）。第 5 章新增，走 RAG：
     * 答案在规则文档里，不在商城接口里。
     *
     * <p>为什么和 AFTER_SALES 分开：两者现在都走 RAG，分错了也不影响回答；但「我要退这一单」是要<b>办事</b>（第 7 章的 Agent 会接手），
     * 「七天无理由要什么条件」只是<b>问规则</b>。v1 提示词里没有规则类，「积分多久过期」「运费怎么算」只能落到 UNKNOWN，
     * 按普通问答回答，模型只能凭常识编。
     */
    POLICY,
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
