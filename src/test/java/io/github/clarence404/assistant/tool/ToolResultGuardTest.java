package io.github.clarence404.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 第 3 章：回答的事后检查 {@link ToolResultGuard}：工具说没查到订单，回答却说出了订单状态，就换成工具给的原因；回答是一段没被识别的调用意图 JSON，换成请用户重说。
 *
 * <p>手法：直接构造工具调用记录（结果是工具会返回的 JSON），不需要模型。只在最有把握的情况下改写，其余原样放过。
 */
class ToolResultGuardTest {

    private static final ToolInvocation NOT_LOGGED_IN = new ToolInvocation(
            "queryOrderStatus",
            ToolInvocation.Source.DIRECT,
            "{\"orderNo\":\"21182516707889029120785\"}",
            "{\"found\":false,\"reason\":\"还没有登录，查不了订单：请先在页面右上角登录商城账号\",\"orderNo\":\"21182516707889029120785\"}",
            3);

    private static final ToolInvocation FOUND = new ToolInvocation(
            "queryOrderStatus",
            ToolInvocation.Source.MODEL,
            "{\"orderNo\":\"21182516707889029120785\"}",
            "{\"found\":true,\"status\":\"待发货（已付款，商家还没发货）\"}",
            12);

    @Test
    void inventedStatusAfterNotFoundIsReplacedByReason() {
        String checked = ToolResultGuard.check("您的订单已发货，预计明天送达。", List.of(NOT_LOGGED_IN));

        assertThat(checked).startsWith("抱歉，").contains("请先在页面右上角登录").doesNotContain("已发货");
    }

    /** qwen2.5:1.5b 录制时的真实输出：想调工具，却把调用意图当成文字说了出来。 */
    @Test
    void leakedToolCallJsonIsNotShownToUser() {
        String leaked = "{\"name\": \"queryProduct\", \"arguments\": {\"skuId\": \"20001\"}}";

        assertThat(ToolResultGuard.check(leaked, List.of())).isEqualTo(ToolResultGuard.LEAKED_TOOL_CALL_REPLY);
        assertThat(ToolResultGuard.check("商品 20001 有货，价格 39.90 元。", List.of())).isEqualTo("商品 20001 有货，价格 39.90 元。");
    }

    @Test
    void honestReplyIsKept() {
        String reply = "您还没有登录，请先登录后再查订单。";

        assertThat(ToolResultGuard.check(reply, List.of(NOT_LOGGED_IN))).isEqualTo(reply);
    }

    @Test
    void statusIsFineWhenSomeLookupFoundTheOrder() {
        String reply = "您的订单目前是「待发货」。";

        assertThat(ToolResultGuard.check(reply, List.of(NOT_LOGGED_IN, FOUND))).isEqualTo(reply);
        assertThat(ToolResultGuard.check(reply, List.of())).as("没调工具不检查").isEqualTo(reply);
    }
}
