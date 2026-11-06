package io.github.clarence404.assistant.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 每次调用独占的临时目录：{@code root/} 下放系统提示词、Schema 等输入文件，{@code root/cwd/} 是空目录，作为工具进程的工作目录。
 *
 * <p>工作目录为空：工具读不到任何项目文件（也就读不到项目里的 CLAUDE.md / AGENTS.md），调用结束后整个目录删除。
 */
public final class CliWorkspace implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CliWorkspace.class);

    private final Path root;
    private final Path cwd;
    private final AtomicBoolean closed = new AtomicBoolean();

    private CliWorkspace(Path root, Path cwd) {
        this.root = root;
        this.cwd = cwd;
    }

    /** 在 parent 下新建一个临时目录。 */
    public static CliWorkspace create(Path parent) {
        try {
            Files.createDirectories(parent);
            Path root = Files.createTempDirectory(parent, "mall-assistant-cli-");
            return new CliWorkspace(root, Files.createDirectory(root.resolve("cwd")));
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建命令行调用的临时目录：" + parent, e);
        }
    }

    public Path root() {
        return root;
    }

    /** 工具进程的工作目录：空目录。 */
    public Path cwd() {
        return cwd;
    }

    /** 在 root 下写一个 UTF-8 文本文件（不在工作目录里），返回其绝对路径。 */
    public Path write(String fileName, String content) throws IOException {
        Path file = root.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toAbsolutePath();
    }

    /** 删除整个临时目录；可重复调用。删不掉（Windows 上进程尚未完全退出）只记日志。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("删除临时文件失败：{}", path, e);
                }
            });
        } catch (IOException e) {
            log.debug("清理临时目录失败：{}", root, e);
        }
        if (Files.exists(root)) {
            log.warn("临时目录没有删干净：{}", root);
        }
    }
}
