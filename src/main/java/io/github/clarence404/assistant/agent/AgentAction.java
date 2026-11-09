package io.github.clarence404.assistant.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * 校验、整理过的一步动作：哪个工具、哪些参数（第 7 章引入）。
 *
 * <p>所在层：Agent 的领域模型。由 {@link #parse} 从模型的 {@link AgentDecision} 得到；{@link AgentLoop} 和 {@link NativeAgentRunner} 拿它去执行
 * 只读工具或生成待确认操作，{@link #key()} 用来判断「是不是又在做同一件事」。
 *
 * <p>参数只保留这个工具用得到的那几个（模型在别的字段里填的东西一律丢掉），按固定顺序放进 {@link LinkedHashMap}：同样的调用得到同样的
 * {@link #argumentsJson()}，重复检测和录制回放才稳定。
 *
 * @param tool 工具
 * @param arguments 参数（只含这个工具用得到的、非空的），不可修改
 * @param thought 模型这一步的想法，可以为 null
 */
public record AgentAction(AgentTool tool, Map<String, String> arguments, String thought) {

    /** 订单号：19 位雪花 ID，或 19 位 + 4 位用户基因共 23 位（和第 2 章 IntentResult 同一个规则）。 */
    static final Pattern ORDER_NO = Pattern.compile("\\d{19}|\\d{23}");

    /** 商品编号：long 型 ID。 */
    static final Pattern SKU_ID = Pattern.compile("\\d{1,19}");

    /** 规则查询、售后原因、回答、想法的长度上限：模型写一大段时截短，不让它把上下文撑满。 */
    static final int MAX_QUERY_CHARS = 100;

    static final int MAX_REASON_CHARS = 200;
    static final int MAX_ANSWER_CHARS = 500;
    static final int MAX_THOUGHT_CHARS = 200;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 规范化：参数不可修改。 */
    public AgentAction {
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /**
     * 取一个参数。
     *
     * @param name 参数名，如 orderNo
     * @return 值；没有时为 null
     */
    public String argument(String name) {
        return arguments.get(name);
    }

    /** 参数的 JSON（按固定顺序），如 {@code {"orderNo":"2118..."}}；没有参数时是 {@code {}}。 */
    public String argumentsJson() {
        return JSON.writeValueAsString(arguments);
    }

    /** 「同一个调用」的键：工具名 + 参数。同一个键执行超过 {@code max-same-calls} 次就不再执行。 */
    public String key() {
        return tool.toolName() + " " + argumentsJson();
    }

    /**
     * 把模型给的下一步动作校验、整理成可以执行的动作（ch07-1）。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>decision 为 null（模型输出解析不了）：无效，原因「输出看不懂」之类
     *   <li>{@link AgentTool#from} 认不出 action：无效，原因里列出全部工具名（{@link AgentTool#names()}），让模型下一步改正
     *   <li>每个值先 {@link #clean}：去掉首尾空白；空串、{@code "null"}、{@code "无"} 都当作没填（小模型常常把 null 写成字符串）
     *   <li>按工具检查必填参数，缺了或格式不对就无效，原因要说清楚缺什么、应该是什么样：
     *       <ul>
     *         <li>listMyOrders：无参数
     *         <li>queryOrder、queryPayment、cancelOrder：orderNo，去掉中间的空格后要符合 {@link #ORDER_NO}
     *         <li>queryProduct：input 作为 skuId，符合 {@link #SKU_ID}
     *         <li>searchPolicy：input 作为 query，超过 {@link #MAX_QUERY_CHARS} 字截短
     *         <li>changeAddress：orderNo，input 作为 address（新的详细地址；长度和来源由 {@link WriteActionGuard} 再查）
     *         <li>applyAfterSales：orderNo，input 作为 reason（超过 {@link #MAX_REASON_CHARS} 字截短）
     *         <li>finish：input 作为 answer（超过 {@link #MAX_ANSWER_CHARS} 字截短）
     *       </ul>
     *       模型只填一个通用的 input（见 {@link AgentDecision} 为什么只有四个字段），代码按工具给它起名字放进参数：之后的校验、确认卡片、执行都用这个名字
     *   <li>参数按上面列的顺序放；thought 清理后超过 {@link #MAX_THOUGHT_CHARS} 字截短，可以为 null
     * </ul>
     *
     * <p>为什么无效的动作不直接报错结束：模型这一步犯的错，告诉它错在哪，它下一步多半能改对（和第 2 章「解析失败重试一次」一个道理）。
     * 由 {@link AgentLoop} 把原因作为这一步的结果写进办事记录，算一步，步数上限兜底。
     *
     * @param decision 模型的输出，可以为 null
     * @return 解析结果：要么是动作，要么是给模型看的错误原因
     */
    public static Parsed parse(AgentDecision decision) {
        // TODO ch07-1：decision 为 null、认不出工具时无效；每个值先 clean；按工具检查必填参数，把通用的 input 按工具起名字放进参数（规则见上面的 Javadoc）
        return Parsed.invalid(decision == null ? null : decision.thought(), "TODO ch07-1");
    }

    /** 订单号：去掉中间的空格后校验格式，放进参数；不对时返回原因。 */
    private static String orderNo(AgentDecision decision, Map<String, String> args) {
        String no = clean(decision.orderNo());
        no = no == null ? null : no.replaceAll("\\s", "");
        if (no == null || !ORDER_NO.matcher(no).matches()) {
            return "需要 orderNo：19 位或 23 位数字，只能照抄用户的话或查询结果里的订单号，不要编";
        }
        args.put("orderNo", no);
        return null;
    }

    /**
     * 清理模型填的一个值：去掉首尾空白；空串、{@code "null"}、{@code "none"}、{@code "无"} 当作没填。
     *
     * @param value 原值，可以为 null
     * @return 清理后的值；没填时为 null
     */
    static String clean(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty()
                        || "null".equalsIgnoreCase(stripped)
                        || "none".equalsIgnoreCase(stripped)
                        || "无".equals(stripped)
                ? null
                : stripped;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 解析结果：动作和错误原因二选一。
     *
     * @param action 动作；无效时为 null
     * @param thought 模型的想法（无效时也留着，调试面板显示）
     * @param error 无效的原因（给模型看，写进下一步的办事记录）；有效时为 null
     */
    public record Parsed(AgentAction action, String thought, String error) {

        /** 有效。 */
        static Parsed ok(AgentAction action) {
            return new Parsed(action, action.thought(), null);
        }

        /** 无效。 */
        static Parsed invalid(String thought, String error) {
            return new Parsed(null, thought, error);
        }

        /** 是否有效。 */
        public boolean valid() {
            return action != null;
        }
    }
}
