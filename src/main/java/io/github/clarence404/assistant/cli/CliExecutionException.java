package io.github.clarence404.assistant.cli;

/**
 * 命令行工具调用失败：找不到命令、退出码非 0、输出解析不了、工具报告出错。归为 MODEL_UNAVAILABLE。
 *
 * <p>消息里可能带 stderr 末尾几行，只进日志：{@code ApiExceptionHandler} 返回给前端的是固定话术。
 *
 * <p>架构层：命令行模型。不属于某一章的练习，是第 2 章之后加的可选扩展（{@code claude-cli} / {@code codex-cli} profile 才启用）。
 * 抛出方：{@link CliCommandResolver}（找不到命令）、{@link CliProcessRunner}（启动失败、退出码非 0）、各 {@link CliDialect}
 * （输出里没有结果、工具自己报告失败）。它不是超时类异常，所以 {@code ModelCallException#from} 归为 MODEL_UNAVAILABLE（503）。
 *
 * <p>注意它和「模型输出的 JSON 解析失败」不是一回事：工具正常返回但内容不合格式时，意图识别走第 2 章的重试与兜底；只有工具本身调不通才抛这个异常。
 */
public class CliExecutionException extends RuntimeException {

    /** @param message 失败原因（可能带 stderr 末尾，只进日志） */
    public CliExecutionException(String message) {
        super(message);
    }

    /**
     * @param message 失败原因
     * @param cause 底层异常（如启动进程时的 IOException）
     */
    public CliExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
