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

/** 用 {@link FakeCli} 组装 {@link CliProperties}，并读回它记下的参数与输入。 */
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

    public static List<String> commandArgs() {
        return List.of("-cp", testClassesDir(), FakeCli.class.getName());
    }

    public static CliProperties properties(
            CliFlavor flavor, Path workDir, Path record, String mode, Duration timeout, boolean nativeStructured) {
        return properties(flavor, workDir, record, mode, timeout, nativeStructured, Map.of());
    }

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

    public static String recorded(Path record, String suffix) throws IOException {
        return Files.readString(Path.of(record + suffix), StandardCharsets.UTF_8);
    }
}
