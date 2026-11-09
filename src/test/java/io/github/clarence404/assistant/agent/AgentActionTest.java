package io.github.clarence404.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.mall.StubMallApi;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 第 7 章 TODO ch07-1：把模型给的下一步动作（{@link AgentDecision}）校验、整理成 {@link AgentAction}。
 *
 * <p>思路：模型的输出只当作「提议」——工具名可能写错、订单号可能抄错位、null 可能写成字符串、该空着的字段填了东西。能看懂的整理好（只留这个工具用得到的参数，
 * 通用的 input 按工具起好名字），看不懂的给出一句能让模型改正的原因。纯函数，不需要模型和商城。
 */
class AgentActionTest {

    private static final String ORDER = StubMallApi.PENDING_PAYMENT_ORDER_NO;

    private static AgentAction.Parsed parse(String action, String orderNo, String input) {
        return AgentAction.parse(new AgentDecision("想一想", action, orderNo, input));
    }

    /** 查订单：订单号去掉中间空格，只保留 orderNo，input 里填的东西丢掉。 */
    @Test
    void queryOrderKeepsOnlyOrderNo() {
        AgentAction.Parsed parsed = parse("queryOrder", ORDER.substring(0, 10) + " " + ORDER.substring(10), "顺便说点别的");

        assertThat(parsed.valid()).isTrue();
        assertThat(parsed.action().tool()).isEqualTo(AgentTool.QUERY_ORDER);
        assertThat(parsed.action().arguments()).containsExactly(Map.entry("orderNo", ORDER));
        assertThat(parsed.action().argumentsJson()).isEqualTo("{\"orderNo\":\"" + ORDER + "\"}");
        assertThat(parsed.action().thought()).isEqualTo("想一想");
    }

    /** 工具名宽松匹配：大小写、下划线、空格都认。 */
    @Test
    void toolNameIsMatchedLeniently() {
        assertThat(parse(" ListMyOrders ", "", "").action().tool()).isEqualTo(AgentTool.LIST_MY_ORDERS);
        assertThat(parse("list_my_orders", null, null).action().tool()).isEqualTo(AgentTool.LIST_MY_ORDERS);
    }

    /** 认不出的工具：无效，原因里列出全部工具名；不去猜（cancel 不当作 cancelOrder）。 */
    @Test
    void unknownToolIsRejectedWithToolList() {
        AgentAction.Parsed parsed = parse("cancel", ORDER, "");

        assertThat(parsed.valid()).isFalse();
        assertThat(parsed.error()).contains("cancel", "cancelOrder", "finish");
    }

    /** 解析不了（null）：无效。 */
    @Test
    void unreadableOutputIsRejected() {
        AgentAction.Parsed parsed = AgentAction.parse(null);

        assertThat(parsed.valid()).isFalse();
        assertThat(parsed.error()).contains("看不懂");
    }

    /** 订单号格式不对、写成字符串 "null"、空串：无效，说明要 19 位或 23 位。 */
    @Test
    void badOrderNoIsRejected() {
        assertThat(parse("cancelOrder", "12345", "").error()).contains("19 位或 23 位");
        assertThat(parse("queryOrder", "null", "").valid()).isFalse();
        assertThat(parse("queryPayment", "  ", "").valid()).isFalse();
    }

    /** 改地址：input 是新的详细地址（去掉首尾空白），没有就无效。 */
    @Test
    void changeAddressTakesAddressFromInput() {
        assertThat(parse("changeAddress", ORDER, "null").error()).contains("新的详细地址");

        AgentAction action = parse("changeAddress", ORDER, " 杭州市文三路 100 号 ").action();
        assertThat(action.arguments())
                .containsExactly(Map.entry("orderNo", ORDER), Map.entry("address", "杭州市文三路 100 号"));
    }

    /** 售后：要订单号和原因（input），原因过长截短。 */
    @Test
    void applyAfterSalesNeedsReason() {
        assertThat(parse("applyAfterSales", ORDER, "").error()).contains("售后原因");

        AgentAction action = parse("applyAfterSales", ORDER, "坏".repeat(300)).action();
        assertThat(action.argument("reason")).hasSize(AgentAction.MAX_REASON_CHARS);
    }

    /** 查规则、查商品、finish 的 input 分别叫 query、skuId、answer；listMyOrders 不要参数（填了也丢掉）。 */
    @Test
    void inputIsNamedByTool() {
        assertThat(parse("searchPolicy", "", "").valid()).isFalse();
        assertThat(parse("searchPolicy", "", "退款多久到账").action().argument("query"))
                .isEqualTo("退款多久到账");
        assertThat(parse("queryProduct", "", "红心柚").valid()).isFalse();
        assertThat(parse("queryProduct", "", "20001").action().argument("skuId"))
                .isEqualTo("20001");
        assertThat(parse("finish", "", "无").valid()).isFalse();
        assertThat(parse("finish", ORDER, "好的").action().arguments()).containsOnlyKeys("answer");
        assertThat(parse("listMyOrders", ORDER, "随便").action().arguments()).isEmpty();
    }

    /** 重复检测的键：工具名 + 参数，同样的调用得到同样的键。 */
    @Test
    void sameCallHasSameKey() {
        AgentAction a =
                AgentAction.parse(AgentDecision.of("一", "queryOrder", ORDER)).action();
        AgentAction b = AgentAction.parse(AgentDecision.of("二", "QueryOrder", " " + ORDER))
                .action();

        assertThat(a.key()).isEqualTo(b.key()).isEqualTo("queryOrder {\"orderNo\":\"" + ORDER + "\"}");
    }
}
