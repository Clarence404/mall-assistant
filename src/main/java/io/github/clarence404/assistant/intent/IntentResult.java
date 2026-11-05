package io.github.clarence404.assistant.intent;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.regex.Pattern;

/**
 * 意图识别的结果：模型按这个 record 生成的 JSON Schema 输出，Spring AI 再把 JSON 转回来。
 *
 * <p>字段上的描述会写进 JSON Schema，等于给模型的字段说明。
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

    /** 兜底结果：模型输出解析不了时返回它，不把解析异常抛给用户。 */
    public static IntentResult unknown() {
        return new IntentResult(Intent.UNKNOWN, 0.0, null, null);
    }

    /**
     * 模型输出「结构对了」不等于「内容对了」：intent 为空记为 UNKNOWN，confidence 夹到 [0, 1]（NaN 记为 0），orderNo / skuId
     * 去掉首尾空白后格式不对就置为 null，不把模型编的订单号往下游传。
     */
    public IntentResult sanitized() {
        // TODO ch02-5：返回校验后的新 IntentResult（record 不可变）：
        //  1. intent 为 null → Intent.UNKNOWN
        //  2. confidence 夹到 [0, 1]，NaN 记为 0（模型可能给 95、-1，甚至字符串转过来的怪值）
        //  3. orderNo、skuId 先去掉首尾空白，再分别用 ORDER_NO、SKU_ID 整串匹配，不匹配就置为 null
        //     （小模型会把没有的字段写成字符串 "null"、"}"，或者编一个订单号）
        //  验收：IntentResultTest、IntentServiceTest.resultIsSanitized
        return this;
    }
}
