package io.github.clarence404.assistant.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 命令行模型提供方的配置，前缀 {@code assistant.cli}，由 {@code claude-cli} / {@code codex-cli} profile 设置。
 *
 * @param flavor 用哪个命令行工具：{@code claude}（Claude Code）或 {@code codex}（Codex CLI）；不设置时不启用命令行提供方
 * @param command 可执行文件名或完整路径；为空时用 flavor 的默认名（claude / codex），在 PATH 里查找，Windows 下自动识别 npm 的 .cmd 包装
 * @param commandArgs 紧跟在可执行文件后面的参数（一般为空；测试用它启动假 CLI：{@code -cp <dir> FakeCli}）
 * @param model 模型名：claude 的 {@code --model}（如 haiku、sonnet），codex 的 {@code -m}；为空时用工具自己的默认模型
 * @param timeout 一次调用（含进程启动）最长多久，超时杀掉整棵进程树，按 MODEL_TIMEOUT 处理
 * @param extraArgs 追加的命令行参数（安全相关的参数已经写死在代码里，这里只放额外的）
 * @param workDir 每次调用新建的临时目录放在哪个目录下，默认系统临时目录
 * @param env 额外的环境变量，如 claude 的 {@code CLAUDE_CODE_DISABLE_CLAUDE_MDS=1}
 * @param nativeStructuredOutput 意图识别是否把 JSON Schema 交给工具原生处理（claude 的 {@code --json-schema}、codex 的
 *     {@code --output-schema}）；false 时 Spring AI 退回到把格式说明拼进用户消息
 */
@ConfigurationProperties("assistant.cli")
public record CliProperties(
        CliFlavor flavor,
        String command,
        List<String> commandArgs,
        String model,
        @DefaultValue("120s") Duration timeout,
        List<String> extraArgs,
        Path workDir,
        Map<String, String> env,
        @DefaultValue("true") boolean nativeStructuredOutput) {

    public CliProperties {
        commandArgs = commandArgs == null ? List.of() : List.copyOf(commandArgs);
        extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
        env = env == null ? Map.of() : Map.copyOf(env);
        if (workDir == null) {
            workDir = Path.of(System.getProperty("java.io.tmpdir"));
        }
        if (model != null && model.isBlank()) {
            model = null;
        }
    }

    /** 实际要执行的命令名：配置了就用配置的，否则用 flavor 的默认名。 */
    public String commandOrDefault() {
        if (command != null && !command.isBlank()) {
            return command.strip();
        }
        if (flavor == null) {
            throw new IllegalStateException("没有配置 assistant.cli.flavor（claude / codex）");
        }
        return flavor.defaultCommand();
    }
}
