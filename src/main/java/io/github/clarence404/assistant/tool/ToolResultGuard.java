package io.github.clarence404.assistant.tool;

import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 回答的事后检查（很粗的一道防幻觉），只处理两种最有把握的情况：
 *
 * <ul>
 *   <li>查订单的工具说「没查到」，回答里却出现了「已发货」「待发货」这类状态词：模型无视了工具结果、编了一个状态，换成工具给出的原因
 *   <li>回答本身就是一段 {@code {"name": "...", "arguments": {...}}}：小模型想调工具，却没按约定的格式输出，Ollama 没认出这是调用意图，
 *       把它当普通文字返回了（本章录制时 qwen2.5:1.5b 就出现过）。这段 JSON 不能给用户看，换成一句请用户重新说的话
 * </ul>
 *
 * <p>所在层：工具（第 3 章引入）。上游是 {@code ChatService} 的阻塞回答；流式回答已经一段段推给用户了，收不回来，不做这道检查
 * （更彻底的做法是先在服务端攒完整段再检查，代价是失去流式的体验）。
 *
 * <p>为什么提示词里已经要求「不许编造」还要再查一道：提示词只是「请求」模型，小模型经常不听；能用代码确定地判断出来的错误，
 * 就不要只靠提示词。这里只做上面两种最有把握的判断，宁可漏，不误伤正常回答。
 */
public final class ToolResultGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolResultGuard.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern LEAKED_TOOL_CALL =
            Pattern.compile("^\\s*\\{\\s*\"name\"\\s*:.*\"arguments\".*}\\s*$", Pattern.DOTALL);

    /** 回答是一段没被识别的调用意图时，换成这句话。 */
    static final String LEAKED_TOOL_CALL_REPLY = "抱歉，我刚才没能查到信息。请再说一次，或者直接告诉我订单号或商品编号。";

    private ToolResultGuard() {}

    /**
     * 检查回答。
     *
     * @param reply 模型的回答
     * @param invocations 这次请求的工具调用记录
     * @return 原回答；或在「查订单都没查到、回答里却有状态词」时，换成「抱歉，<工具给出的原因>」；回答是一段调用意图 JSON 时换成
     *     {@link #LEAKED_TOOL_CALL_REPLY}
     */
    public static String check(String reply, List<ToolInvocation> invocations) {
        if (LEAKED_TOOL_CALL.matcher(reply).matches()) {
            log.warn("模型把调用意图当成文字输出了，没有执行任何工具：{}", reply);
            return LEAKED_TOOL_CALL_REPLY;
        }
        List<ToolInvocation> orderLookups = invocations.stream()
                .filter(i -> "queryOrderStatus".equals(i.name()))
                .toList();
        if (orderLookups.isEmpty() || orderLookups.stream().anyMatch(i -> found(i.result()))) {
            return reply;
        }
        if (OrderStatusText.words().stream().noneMatch(reply::contains)) {
            return reply;
        }
        String reason = reason(orderLookups.getLast().result());
        log.warn("回答里出现了订单状态，但工具没有查到订单，按工具结果改写回答。原回答：{}", reply);
        return "抱歉，" + reason;
    }

    /** 工具结果里 found 是否为 true；结果不是 JSON（执行失败）按没查到处理。 */
    private static boolean found(String result) {
        return parse(result).path("found").asBoolean(false);
    }

    private static String reason(String result) {
        String reason = parse(result).path("reason").asString("");
        return reason.isBlank() ? "暂时查不到这笔订单的信息，请稍后再试" : reason;
    }

    private static JsonNode parse(String result) {
        try {
            return JSON.readTree(result == null ? "{}" : result);
        } catch (JacksonException e) {
            return JSON.createObjectNode();
        }
    }
}
