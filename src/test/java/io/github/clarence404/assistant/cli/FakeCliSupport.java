package io.github.clarence404.assistant.cli;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试辅助（第 2 章引入）：用 {@link FakeCli} 组装 {@link CliProperties}，并读回它记下的参数与输入。
 *
 * <p>「命令」配成当前 JVM 的 java 可执行文件，「命令前置参数」是 {@code -cp <target/test-classes> io.github.clarence404.assistant.cli.FakeCli}，
 * CliChatModel 会在它们后面接上 claude / codex 的参数，于是真实的拼参、起进程、解析流程都走了一遍，只是被执行的程序换成了 FakeCli。
 */
public final class FakeCliSupport {

    private FakeCliSupport() {}

    /** 当前 JVM 的 java 可执行文件。 */
    public static String javaExecutable() {
        return ProcessHandle.current()
                .info()
                .command()
                .orElseGet(() ->
                        Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    /** FakeCli.class 所在的目录（target/test-classes）。 */
    public static String testClassesDir() {
        try {
            return Path.of(FakeCli.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 放在 claude / codex 参数前面的固定参数：{@code -cp <test-classes> FakeCli 的类名}。 */
    public static List<String> commandArgs() {
        return List.of("-cp", testClassesDir(), FakeCli.class.getName());
    }

    /** 同下，不加额外环境变量。 */
    public static CliProperties properties(
            CliFlavor flavor, Path workDir, Path record, String mode, Duration timeout, boolean nativeStructured) {
        return properties(flavor, workDir, record, mode, timeout, nativeStructured, Map.of());
    }

    /**
     * 组装指向 FakeCli 的配置。
     *
     * @param flavor 模拟 claude 还是 codex（决定拼哪套参数，FakeCli 据此输出对应格式）
     * @param workDir 每次调用的临时工作目录建在这里下面
     * @param record 记录文件前缀，FakeCli 往 {@code <record>.args} 等文件里写
     * @param mode {@code FAKE_CLI_MODE}：ok / fail / garbage / hang / error-result
     * @param timeout 单次调用超时
     * @param nativeStructured 是否把 JSON Schema 原生交给命令行工具
     * @param extraEnv 额外传给子进程的环境变量，如 {@code FAKE_CLI_REPLY}
     */
    public static CliProperties properties(
            CliFlavor flavor,
            Path workDir,
            Path record,
            String mode,
            Duration timeout,
            boolean nativeStructured,
            Map<String, String> extraEnv) {
        Map<String, String> env = new HashMap<>(extraEnv);
        env.put("FAKE_CLI_MODE", mode);
        env.put("FAKE_CLI_RECORD", record.toString());
        return new CliProperties(
                flavor, javaExecutable(), commandArgs(), "haiku", timeout, List.of(), workDir, env, nativeStructured);
    }

    /** FakeCli 收到的参数。 */
    public static List<String> recordedArgs(Path record) throws IOException {
        return Files.readAllLines(Path.of(record + ".args"), StandardCharsets.UTF_8).stream()
                .map(line -> URLDecoder.decode(line, StandardCharsets.UTF_8))
                .toList();
    }

    /** FakeCli 记下的某个文件的内容，suffix 如 {@code .stdin}、{@code .system}、{@code .schema}。 */
    public static String recorded(Path record, String suffix) throws IOException {
        return Files.readString(Path.of(record + suffix), StandardCharsets.UTF_8);
    }
}
