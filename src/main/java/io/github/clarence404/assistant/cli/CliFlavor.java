package io.github.clarence404.assistant.cli;

import java.util.function.Supplier;

/** 支持的命令行工具。 */
public enum CliFlavor {
    /** Anthropic Claude Code：{@code claude -p}。 */
    CLAUDE("claude", ClaudeCliDialect::new),
    /** OpenAI Codex CLI：{@code codex exec}。 */
    CODEX("codex", CodexCliDialect::new);

    private final String defaultCommand;
    private final Supplier<CliDialect> dialect;

    CliFlavor(String defaultCommand, Supplier<CliDialect> dialect) {
        this.defaultCommand = defaultCommand;
        this.dialect = dialect;
    }

    public String defaultCommand() {
        return defaultCommand;
    }

    public CliDialect dialect() {
        return dialect.get();
    }
}
