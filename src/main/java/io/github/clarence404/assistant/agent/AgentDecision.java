package io.github.clarence404.assistant.agent;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 模型在每一步给出的「下一步动作」：按这个 record 生成的 JSON Schema 输出（结构化输出），Spring AI 再转回 Java 对象（第 7 章引入）。
 *
 * <p>所在层：Agent 的领域模型。{@link AgentPlanner} 让模型输出它，{@link AgentAction#parse} 把它校验、整理成真正要执行的 {@link AgentAction}。
 *
 * <p><b>为什么只有四个字段</b>（动作、订单号、一个通用的文字参数 input、想法），而不是每个参数一个字段：Spring AI 2.0.2 从 record 生成的 Schema 里，
 * <b>每个字段都是必填的字符串</b>（{@code required} 列出全部字段、类型是 {@code string} 不是 {@code ["string","null"]}），Ollama 按 Schema 约束解码，
 * 模型每一步都必须给每个字段填一个字符串。第一版是十个平铺的字段（receiver、phone、address、reason、answer……），录制时 1.5b 把用不到的字段全填上了
 * 编的东西（手机号「12345678901」、地址填成订单号），还在 answer 里无限重复同一句话直到输出上限，一步要 20～60 秒、经常解析失败。
 * 字段越少，模型要「编」的地方越少、输出越短。Schema 里的字段按字母排序（action、input、orderNo、thought），动作正好排在最前面。
 *
 * <p>和第 2 章的 {@code IntentResult} 一样：Schema 只约束「形状」，不保证「内容」对——工具名可能是编的、订单号可能抄错一位、地址可能是模型「润色」过的。
 * 所以这里的值一律当作<b>模型的提议</b>，由代码校验（{@link AgentAction#parse}、{@link WriteActionGuard}）。
 *
 * @param thought 这一步的想法（思考摘要），一句话；只用于调试面板和下一步的办事记录，不影响执行
 * @param action 下一步动作：工具名或 finish
 * @param orderNo 订单号（查订单、查支付单、三个写操作用）；用不到时为空串
 * @param input 这一步要的文字参数：查商品是商品编号，查规则是要查的问题，改地址是新的详细地址，售后是原因，finish 是给用户的回答；用不到时为空串
 */
public record AgentDecision(
        @JsonPropertyDescription("这一步的想法，一句话") String thought,

        @JsonPropertyDescription(
                "下一步动作，只能是 listMyOrders、queryOrder、queryPayment、queryProduct、searchPolicy、cancelOrder、changeAddress、applyAfterSales、finish 之一")
        String action,

        @JsonPropertyDescription("订单号，19 位或 23 位数字，只能照抄用户的话或查询结果；用不到就填空字符串")
        String orderNo,

        @JsonPropertyDescription(
                "文字参数：queryProduct 填商品编号，searchPolicy 填要查的规则，changeAddress 填照抄用户原话的新地址，applyAfterSales 填售后原因，finish 填给用户的回答（不超过三句话）；其余动作填空字符串")
        String input) {

    /**
     * 只有动作和订单号的决定（测试、原生工具调用模式用）。
     *
     * @param thought 想法
     * @param action 动作
     * @param orderNo 订单号
     * @return input 为 null 的决定
     */
    public static AgentDecision of(String thought, String action, String orderNo) {
        return new AgentDecision(thought, action, orderNo, null);
    }
}
