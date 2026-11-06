package io.github.clarence404.assistant.cli;

import java.util.function.Supplier;

/**
 * 支持的命令行工具，即配置项 {@code assistant.cli.flavor} 的取值（yml 里写小写 {@code claude} / {@code codex}，Spring 绑定枚举时不区分大小写）。
 *
 * <p>架构层：命令行模型。不属于某一章的练习，是第 2 章之后加的可选扩展（{@code claude-cli} / {@code codex-cli} profile 才启用）。
 *
 * <p>每个取值绑定两样东西：默认命令名（{@link CliProperties#commandOrDefault()} 没配 command 时用它）和对应的 {@link CliDialect}
 * （{@link CliChatModel} 构造时取一次）。以后要支持第三种工具，加一个取值 + 一个 {@link CliDialect} 实现即可，其余代码不用动。
 */
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

    /** 没有配置 {@code assistant.cli.command} 时在 PATH 里查找的命令名。 */
    public String defaultCommand() {
        return defaultCommand;
    }

    /** 新建一个这种工具的方言实例（方言本身无状态，流式解析的状态在每次新建的 {@link CliDialect.StreamParser} 里）。 */
    public CliDialect dialect() {
        return dialect.get();
    }
}
