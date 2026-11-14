package io.github.clarence404.assistant.chat;

/**
 * 这一轮走哪条路（审查修复时抽出来的路由决策，阻塞和流式两个接口共用）。
 *
 * <p>所在层：业务层，由 {@link ChatService#route} 算出来，{@code ChatService} 的阻塞回答和流式回答各自 {@code switch} 它。
 *
 * <p><b>为什么要它</b>：原来「先判什么后判什么」在阻塞和流式里各写了一遍，而且顺序不一样——阻塞先判拒答，流式先判「聊天里回确认」和 Agent。
 * 结果同一句话在两个接口上可能走不同的路：有待确认操作时在聊天里回一句「确认」，如果意图识别把它分成了超出范围，阻塞接口回的是拒答话术，
 * 流式接口回的是「请点确认按钮」。判断顺序是业务规则，应该只有一份；两条路只在「怎么把回答交出去」（一次返回还是逐段推）上不同。
 *
 * <p>用 sealed 接口 + record：{@code switch} 时编译器会检查每一种都处理到了，以后加一条路，漏改哪一处编译就不过。
 *
 * <p>判断顺序（{@link ChatService#route}，从上到下，先中先走）：
 *
 * <ol>
 *   <li>{@link ConfirmInChat}：登录了、这个会话有待确认操作、却在聊天里回「确认」——固定话术提醒去点按钮，什么都不执行
 *   <li>{@link Agent}：登录了、要办售后的事——交给 Agent
 *   <li>{@link Refuse}：超出范围而且把握够大——拒答话术，不调回答模型
 *   <li>{@link Rag}：售后、规则政策——检索知识库再回答
 *   <li>{@link Tools}：其余（查订单、问商品、闲聊、未识别）——直接查询或交给模型调工具
 * </ol>
 */
public sealed interface ChatRoute {

    /**
     * 在聊天里回了「确认」：只回一句提醒。
     *
     * @param reply 提醒的固定话术
     */
    record ConfirmInChat(String reply) implements ChatRoute {}

    /** 交给售后 Agent 多步办理。 */
    record Agent() implements ChatRoute {}

    /** 超出范围：拒答话术。 */
    record Refuse() implements ChatRoute {}

    /**
     * 检索增强回答。
     *
     * @param loginHint 没登录却想办事时，接在回答后面的「请先登录」提示；不需要时为 null
     */
    record Rag(String loginHint) implements ChatRoute {}

    /** 第 3 章的路径：按意图直接查询，或交给模型调工具。 */
    record Tools() implements ChatRoute {}
}
