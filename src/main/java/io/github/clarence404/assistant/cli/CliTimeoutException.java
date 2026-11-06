package io.github.clarence404.assistant.cli;

/**
 * 命令行工具在 {@code assistant.cli.timeout} 内没有结束，进程树已被杀掉。
 *
 * <p>类名以 {@code TimeoutException} 结尾，{@code ModelCallException#from} 据此归为 MODEL_TIMEOUT。
 *
 * <p>架构层：命令行模型。不属于某一章的练习，是第 2 章之后加的可选扩展（{@code claude-cli} / {@code codex-cli} profile 才启用）。
 * 抛出方：{@link CliProcessRunner}（看门狗到点杀进程后）。之后的去向：阻塞接口由 {@code ChatService} / {@code IntentService} 转成
 * {@code ModelCallException}，{@code ApiExceptionHandler} 返回 503 + 错误码 MODEL_TIMEOUT；流式接口由 {@code ChatController}
 * 转成一个 error 事件。
 *
 * <p>和 {@link CliExecutionException} 分开，是为了让前端能区分「慢」（换个短问题或重试）和「坏」（服务不可用）。
 */
public class CliTimeoutException extends RuntimeException {

    /** @param message 哪个工具、超过多久（只进日志，前端看到的是 MODEL_TIMEOUT 的固定话术） */
    public CliTimeoutException(String message) {
        super(message);
    }
}
