package io.github.clarence404.assistant.memory;

import io.github.clarence404.assistant.intent.Intent;
import io.github.clarence404.assistant.intent.IntentResult;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.Message;

/**
 * 指代沿用：「订单 2118… 到哪了」→「它什么时候下的单」，第二句里的「它」指的是上一句的订单号（第 6 章引入）。
 *
 * <p>所在层：业务层的纯函数。{@code ChatService} 识别完意图后调用（经 {@link Conversation#carryOver}），把历史里最近提到的订单号 / SKU
 * 补进意图结果，再照第 3 章的直接查询走。
 *
 * <p><b>什么是指代消解</b>：人说话会省略和指代——「它」「这单」「那个」，甚至什么都不说（「什么时候下的单」）。人靠上下文就知道指的是什么；
 * 意图识别只看当前这一句，抽不出订单号，第 3 章的流程就会去「列出我最近的订单」，答非所问。
 *
 * <p><b>两种做法</b>：
 *
 * <ol>
 *   <li>让模型从上文里找：意图识别时把历史一起给它，或者（本项目的做法）先由 {@link QuestionCondenser} 把追问改写成独立问题（「订单 2118… 是什么时候下的单」），
 *       再拿改写结果去识别意图。最通用（「那个红色的呢」这种也能处理），但要多一次模型调用，而且小模型可能<b>抄错号</b>——23 位数字少抄、多抄一位，
 *       格式校验照样通过，查到的是别人的单（商城会返回 404）或者根本不存在的单
 *   <li>代码记住「最近提到的实体」：在最近 {@code entity-turns} 轮的历史文字里，用和第 2 章同样的格式规则找订单号（19 / 23 位数字）和 SKU
 *       （「SKU 20001」「商品编号 20001」），意图是查订单 / 问商品、但没带号时补上。确定、零成本；代价是只认得出格式明确的实体
 * </ol>
 *
 * 本项目两种一起用：意图识别看改写后的问题；这里再兜底——模型给的订单号 / SKU 必须在用户原话或历史里<b>原样出现过</b>，否则当作抄错了扔掉；
 * 没有号时从历史里沿用。改写关掉或失败时，意图识别只看原话（实测 1.5b 对「它什么时候下的单」只看这一句也能分成 ORDER_STATUS），订单号全靠这里沿用。
 *
 * <p><b>什么时候不沿用</b>：用户明说要看列表（「我有哪些订单」「全部订单」）时，沿用上一笔反而错了；只看最近几轮，再早的话题多半已经换了。
 */
public final class ConversationEntities {

    /** 订单号：19 位或 23 位数字，前后不能再接数字（和 {@code IntentResult} 的校验规则一致）。 */
    static final Pattern ORDER_NO = Pattern.compile("(?<!\\d)(\\d{23}|\\d{19})(?!\\d)");

    /** SKU：要有「SKU」或「商品编号」字样，光是一串短数字（「7 天」「20 元」）不算。 */
    static final Pattern SKU = Pattern.compile("(?i)(?:sku|商品编号)\\s*[:：#]?\\s*(\\d{1,19})(?!\\d)");

    /** 明说要看列表的说法：这时不沿用上一笔订单号。 */
    static final Pattern LIST_REQUEST = Pattern.compile("哪些|哪几|全部|所有|列表|列出|几笔|几单|多少单|多少笔|几个订单");

    private ConversationEntities() {}

    /**
     * 沿用的结果。
     *
     * @param intent 补上实体之后的意图（没补时就是原来的）
     * @param orderNo 这一轮从历史里沿用的订单号，没沿用时为 null
     * @param skuId 这一轮从历史里沿用的 SKU，没沿用时为 null
     */
    public record Carried(IntentResult intent, String orderNo, String skuId) {}

    /**
     * 按需从历史里补上订单号 / SKU。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>先核对模型给的 orderNo / skuId：在用户原话和历史文字里都没<b>原样</b>出现过的，置为 null（{@link #mentioned}）
     *   <li>意图是 ORDER_STATUS、没有 orderNo、这一句不是要列表（{@link #LIST_REQUEST}）：在最近 {@code maxTurns} 轮里找最近提到的订单号
     *       （{@link #lastMatch}，ORDER_NO 取第 1 组），找到就补上
     *   <li>意图是 PRODUCT_INFO、没有 skuId：同样找最近提到的 SKU（SKU 取第 1 组）
     *   <li>其他情况原样返回，沿用字段都为 null
     * </ul>
     *
     * @param intent 意图识别的结果（已校验）
     * @param message 用户这一句
     * @param history 带给模型的历史（{@link HistoryWindow#trim} 的结果），按时间从早到晚
     * @param maxTurns 只看最近几轮
     * @return 沿用结果
     */
    public static Carried carryOver(IntentResult intent, String message, List<Message> history, int maxTurns) {
        if (intent.orderNo() != null && !mentioned(intent.orderNo(), message, history)) {
            intent = intent.withOrderNo(null);
        }
        if (intent.skuId() != null && !mentioned(intent.skuId(), message, history)) {
            intent = intent.withSkuId(null);
        }
        if (intent.intent() == Intent.ORDER_STATUS
                && intent.orderNo() == null
                && !LIST_REQUEST.matcher(message).find()) {
            String orderNo = lastMatch(history, maxTurns, ORDER_NO);
            if (orderNo != null) {
                return new Carried(intent.withOrderNo(orderNo), orderNo, null);
            }
        }
        if (intent.intent() == Intent.PRODUCT_INFO && intent.skuId() == null) {
            String skuId = lastMatch(history, maxTurns, SKU);
            if (skuId != null) {
                return new Carried(intent.withSkuId(skuId), null, skuId);
            }
        }
        return new Carried(intent, null, null);
    }

    /**
     * 这个号在用户原话或历史文字里原样出现过吗（模型抄过来的号要核对，见类注释）。
     *
     * @param value 订单号或 SKU
     * @param message 用户原话
     * @param history 历史
     * @return 出现过时为 true
     */
    static boolean mentioned(String value, String message, List<Message> history) {
        return message.contains(value)
                || history.stream().anyMatch(m -> MemoryMessages.text(m).contains(value));
    }

    /**
     * 在最近 maxTurns 轮里，从最新的消息往前找，返回第一个匹配的第 1 组（同一条消息里有好几个时取最先出现的那个）。
     *
     * <p>用户消息和助手消息都看：用户可能说「订单 2118… 到哪了」，也可能是助手列出了最近的几笔订单（从新到旧，最先出现的是最新的一笔）。
     * 「第二笔呢」这种按序号指代的，这里处理不了（见 docs/ch06.md 的思考题）。
     *
     * @param history 历史
     * @param maxTurns 只看最近几轮
     * @param pattern 实体的格式
     * @return 实体；没找到时为 null
     */
    static String lastMatch(List<Message> history, int maxTurns, Pattern pattern) {
        List<List<Message>> turns = HistoryWindow.turns(history);
        for (int t = turns.size() - 1; t >= Math.max(0, turns.size() - maxTurns); t--) {
            List<Message> turn = turns.get(t);
            for (int i = turn.size() - 1; i >= 0; i--) {
                Matcher matcher = pattern.matcher(MemoryMessages.text(turn.get(i)));
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return null;
    }
}
