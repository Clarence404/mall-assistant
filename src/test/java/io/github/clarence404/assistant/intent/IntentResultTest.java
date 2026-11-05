package io.github.clarence404.assistant.intent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 第 2 章：模型输出「结构对了」之后的内容校验。 */
class IntentResultTest {

    @Test
    void confidenceIsClampedToZeroOne() {
        assertThat(new IntentResult(Intent.CHITCHAT, 1.7, null, null)
                        .sanitized()
                        .confidence())
                .isEqualTo(1.0);
        assertThat(new IntentResult(Intent.CHITCHAT, -0.2, null, null)
                        .sanitized()
                        .confidence())
                .isEqualTo(0.0);
        assertThat(new IntentResult(Intent.CHITCHAT, Double.NaN, null, null)
                        .sanitized()
                        .confidence())
                .isEqualTo(0.0);
        assertThat(new IntentResult(Intent.CHITCHAT, 0.42, null, null)
                        .sanitized()
                        .confidence())
                .isEqualTo(0.42);
    }

    @Test
    void missingIntentBecomesUnknown() {
        assertThat(new IntentResult(null, 0.9, null, null).sanitized().intent()).isEqualTo(Intent.UNKNOWN);
    }

    @Test
    void orderNoMustLookLikeMallOrderNo() {
        assertThat(orderNo("1849203385720832512")).isEqualTo("1849203385720832512"); // 19 位
        assertThat(orderNo("18492033857208325120001")).isEqualTo("18492033857208325120001"); // 23 位
        assertThat(orderNo(" 1849203385720832512 ")).isEqualTo("1849203385720832512"); // 去掉首尾空白
        assertThat(orderNo("ABC123")).isNull();
        assertThat(orderNo("2118")).isNull(); // 位数不对，多半是模型编的
        assertThat(orderNo("")).isNull();
        assertThat(orderNo(null)).isNull();
    }

    @Test
    void skuIdMustBeNumeric() {
        assertThat(new IntentResult(Intent.PRODUCT_INFO, 0.8, null, "20002")
                        .sanitized()
                        .skuId())
                .isEqualTo("20002");
        assertThat(new IntentResult(Intent.PRODUCT_INFO, 0.8, null, "蓝牙耳机")
                        .sanitized()
                        .skuId())
                .isNull();
    }

    @Test
    void unknownIsTheSafeFallback() {
        assertThat(IntentResult.unknown()).isEqualTo(new IntentResult(Intent.UNKNOWN, 0.0, null, null));
    }

    @Test
    void intentLabelsAreParsedLeniently() {
        assertThat(Intent.from("order_status")).isEqualTo(Intent.ORDER_STATUS);
        assertThat(Intent.from(" Out-Of-Scope ")).isEqualTo(Intent.OUT_OF_SCOPE);
        assertThat(Intent.from("查订单")).isEqualTo(Intent.UNKNOWN);
        assertThat(Intent.from(null)).isEqualTo(Intent.UNKNOWN);
    }

    private static String orderNo(String value) {
        return new IntentResult(Intent.ORDER_STATUS, 0.9, value, null)
                .sanitized()
                .orderNo();
    }
}
