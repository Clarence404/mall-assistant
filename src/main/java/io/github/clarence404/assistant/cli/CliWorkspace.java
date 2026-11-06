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
 *
 * <p>架构层：命令行模型。不属于某一章的练习，是第 2 章之后加的可选扩展（{@code claude-cli} / {@code codex-cli} profile 才启用）。
 * 上游：{@link CliChatModel} 每次调用 {@link #create} 一个、用完 {@link #close}（阻塞用 try-with-resources，流式用
 * {@code Flux.using}）；下游：{@link CliDialect} 往里写系统提示词、Schema 文件，{@link CliProcessRunner} 把 {@link #cwd()} 设为进程工作目录。
 *
 * <p>为什么要空目录：编码代理以工作目录为「项目」，会读取其中的说明文件（CLAUDE.md / AGENTS.md），那是写给编码助手的指令，
 * 混进来会污染客服的提示词。输入文件放在 {@code root/} 而不是 {@code cwd/}，也是为了让工作目录保持为空。
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

    /**
     * 在 parent 下新建一个临时目录（名字以 {@code mall-assistant-cli-} 开头），并在里面建好空的 {@code cwd/}。
     *
     * @param parent 父目录，即 {@code assistant.cli.work-dir}，不存在时自动创建
     * @return 新的工作区，调用方负责 {@link #close()}
     * @throws UncheckedIOException 创建目录失败
     */
    public static CliWorkspace create(Path parent) {
        try {
            Files.createDirectories(parent);
            Path root = Files.createTempDirectory(parent, "mall-assistant-cli-");
            return new CliWorkspace(root, Files.createDirectory(root.resolve("cwd")));
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建命令行调用的临时目录：" + parent, e);
        }
    }

    /** 临时目录本身：存放输入文件，不作为工作目录。 */
    public Path root() {
        return root;
    }

    /** 工具进程的工作目录：空目录。 */
    public Path cwd() {
        return cwd;
    }

    /**
     * 在 root 下写一个 UTF-8 文本文件（不在工作目录里），返回其绝对路径。
     *
     * <p>系统提示词和 Schema 用文件传，而不是直接塞进命令行参数：内容长、带换行和引号，经命令行容易被转义坏或超出长度限制。
     *
     * @param fileName 文件名，如 {@code system-prompt.txt}
     * @param content 文件内容
     * @return 文件的绝对路径，拼进命令行参数
     * @throws IOException 写文件失败
     */
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
