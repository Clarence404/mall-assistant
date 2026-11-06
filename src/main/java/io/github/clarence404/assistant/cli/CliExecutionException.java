package io.github.clarence404.assistant.cli;

/**
 * 命令行工具调用失败：找不到命令、退出码非 0、输出解析不了、工具报告出错。归为 MODEL_UNAVAILABLE。
 *
 * <p>消息里可能带 stderr 末尾几行，只进日志：{@code ApiExceptionHandler} 返回给前端的是固定话术。
 */
public class CliExecutionException extends RuntimeException {

    public CliExecutionException(String message) {
        super(message);
    }

    public CliExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
