package io.github.clarence404.assistant.cli;

/**
 * 命令行工具在 {@code assistant.cli.timeout} 内没有结束，进程树已被杀掉。
 *
 * <p>类名以 {@code TimeoutException} 结尾，{@code ModelCallException#from} 据此归为 MODEL_TIMEOUT。
 */
public class CliTimeoutException extends RuntimeException {

    public CliTimeoutException(String message) {
        super(message);
    }
}
