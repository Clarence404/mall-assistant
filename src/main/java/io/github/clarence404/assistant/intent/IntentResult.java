package io.github.clarence404.assistant.intent;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.regex.Pattern;

/**
 * 意图识别的结果：模型按这个 record 生成的 JSON Schema 输出，Spring AI 再把 JSON 转回来。
 *
 * <p>字段上的描述会写进 JSON Schema，等于给模型的字段说明。
 *
 * <p>所在层：业务层的领域模型（第 2 章引入）。{@link IntentService} 用它生成 Schema、接收模型输出；{@code ChatService}
 * 据它决定是否拒答；{@code ChatReply} 和流式的 intent 事件把它原样返回给前端。
 *
 * <p>「结构化输出」是什么：默认情况下模型输出的是一段自由文本，程序很难可靠地从里面取字段。结构化输出就是要求模型按约定的 JSON
 * 结构输出，Spring AI 的 {@code entity(IntentResult.class)} 会根据这个 record 自动生成 JSON Schema（字段名、类型、枚举取值、
 * 描述），交给模型，再把模型返回的 JSON 用 Jackson 转回 record。注意 Schema 只约束「形状」，不保证「内容」对，所以还要
 * {@link #sanitized()} 再校验一遍。
 *
 * @param intent 意图
 * @param confidence 模型自评的把握，0～1；小模型的这个值不太可信，只作参考
 * @param orderNo 订单号，没有或格式不对时为 null
 * @param skuId 商品编号，没有或格式不对时为 null
 */
public record IntentResult(
        @JsonPropertyDescription("用户消息的意图") Intent intent,
        @JsonPropertyDescription("对分类有多大把握，0 到 1 之间的小数") double confidence,
        @JsonPropertyDescription("消息里的订单号（一串数字），没有则为 null") String orderNo,

        @JsonPropertyDescription("消息里的商品编号 SKU（一串数字），没有则为 null")
        String skuId) {

    /** mall-evolution 的订单号：19 位雪花 ID，或 19 位 + 4 位用户基因共 23 位。 */
    static final Pattern ORDER_NO = Pattern.compile("\\d{19}|\\d{23}");

    /** mall-evolution 的 SKU 是 long 型 ID。 */
    static final Pattern SKU_ID = Pattern.compile("\\d{1,19}");

    /**
     * 兜底结果：模型输出解析不了时返回它，不把解析异常抛给用户。
     *
     * <p>意图识别只是回答前的「辅助判断」，识别不出来不该让整个对话失败：UNKNOWN 不会触发拒答，照常交给模型回答。
     *
     * @return intent 为 UNKNOWN、confidence 为 0、其余为 null 的结果
     */
    public static IntentResult unknown() {
        return new IntentResult(Intent.UNKNOWN, 0.0, null, null);
    }

    /**
     * 模型输出「结构对了」不等于「内容对了」：intent 为空记为 UNKNOWN，confidence 夹到 [0, 1]（NaN 记为 0），orderNo / skuId
     * 去掉首尾空白后格式不对就置为 null，不把模型编的订单号往下游传。
     *
     * <p>为什么要校验：模型会「幻觉」——一本正经地编出不存在的内容。用户没提订单号，模型也可能凑一串数字填进 orderNo；让它给个
     * 0～1 的把握，它也可能给出 1.5。第 3 章会拿 orderNo 去调真实的订单接口，所以在这里就把格式不对的值挡掉。
     * 这里只校验格式，不校验订单是否真实存在。
     *
     * @return 新的、校验过的结果；原对象不变
     */
    public IntentResult sanitized() {
        Intent safeIntent = intent == null ? Intent.UNKNOWN : intent;
        double safeConfidence = Double.isNaN(confidence) ? 0.0 : Math.clamp(confidence, 0.0, 1.0);
        return new IntentResult(safeIntent, safeConfidence, valid(orderNo, ORDER_NO), valid(skuId, SKU_ID));
    }

    /**
     * 换一个订单号（第 6 章：这一句没提订单号，从会话历史里沿用上一句提到的）。
     *
     * @param value 订单号，同样要符合格式，否则置为 null
     * @return 新的结果；原对象不变
     */
    public IntentResult withOrderNo(String value) {
        return new IntentResult(intent, confidence, valid(value, ORDER_NO), skuId);
    }

    /**
     * 换一个 SKU（第 6 章：从会话历史里沿用）。
     *
     * @param value SKU，同样要符合格式，否则置为 null
     * @return 新的结果；原对象不变
     */
    public IntentResult withSkuId(String value) {
        return new IntentResult(intent, confidence, orderNo, valid(value, SKU_ID));
    }

    /**
     * 格式校验：去掉首尾空白后整串匹配才保留。
     *
     * @return 去掉空白后的值；null 或格式不对时为 null
     */
    private static String valid(String value, Pattern pattern) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return pattern.matcher(stripped).matches() ? stripped : null;
    }
}
