package io.github.clarence404.assistant.eval;

import io.github.clarence404.assistant.chat.ChatReply;

/**
 * 被评测的对象：给一句话，回一个完整的 {@link ChatReply}（第 9 章引入）。
 *
 * <p>所在层：评测。{@link EvalRunner} 只认这个接口，不关心背后是什么：单元测试里是一个直接返回预设回答的 lambda；评测门禁（{@code EvalGateTest}）里是
 * 用录制的真实模型输出回放的整套 {@code ChatService}；真实评测（{@code EvalIT}）里是接着 Ollama 的整套 {@code ChatService}。评测的是<b>整条链路</b>
 * （意图 → 路由 → 工具 / 检索 / Agent → 回答），和用户看到的一样，而不是单独测某一个零件。
 */
@FunctionalInterface
public interface EvalTarget {

    /**
     * 问一句。
     *
     * @param message 这一句
     * @param conversationId 会话 ID（多轮用例同一个，单轮为 null）
     * @param login 是否以演示账号登录
     * @return 完整回答（意图、工具、资料、引用校验、记忆、Agent、待确认操作、Token 用量）
     */
    ChatReply ask(String message, String conversationId, boolean login);
}
