package io.github.clarence404.assistant.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * 启动命令行工具进程：stdin 写入 UTF-8 文本，按 UTF-8 读 stdout，stderr 只保留末尾几行进日志。
 *
 * <p>超时由看门狗处理：到点杀掉整棵进程树（npm 包装下 node 还可能再起子进程），调用方得到 {@link CliTimeoutException}。
 */
public class CliProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(CliProcessRunner.class);

    /** stderr 只保留最后这么多行。 */
    static final int STDERR_TAIL_LINES = 20;

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("cli-watchdog").daemon().factory());

    private final Map<String, String> env;
    private final Duration timeout;
    private final boolean windows;

    public CliProcessRunner(Map<String, String> env, Duration timeout) {
        this(env, timeout, CliCommandResolver.isWindows());
    }

    CliProcessRunner(Map<String, String> env, Duration timeout, boolean windows) {
        this.env = Map.copyOf(env);
        this.timeout = timeout;
        this.windows = windows;
    }

    /** 阻塞执行，返回完整 stdout。 */
    public String run(List<String> command, Path cwd, String stdin) {
        Running running = start(command, cwd, stdin);
        try {
            String stdout = new String(running.process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = running.process.waitFor();
            running.checkExit(exitCode);
            return stdout;
        } catch (IOException e) {
            running.checkTimedOut();
            throw new CliExecutionException("读取 " + running.name + " 的输出失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliExecutionException("等待 " + running.name + " 时被中断", e);
        } finally {
            running.stop();
        }
    }

    /**
     * 流式执行：stdout 每一行发出一个元素；进程正常退出后完成，退出码非 0 或超时以错误结束。取消订阅（下游超时、客户端断开）时杀掉进程树。
     */
    public Flux<String> lines(List<String> command, Path cwd, String stdin) {
        return Flux.create(sink -> {
            Running running;
            try {
                running = start(command, cwd, stdin);
            } catch (RuntimeException e) {
                sink.error(e);
                return;
            }
            sink.onDispose(running::stop);
            Thread.ofVirtual().name("cli-stdout").start(() -> pump(running, sink));
        });
    }

    private static void pump(Running running, FluxSink<String> sink) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(running.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && !sink.isCancelled()) {
                sink.next(line);
            }
            if (sink.isCancelled()) {
                return;
            }
            running.checkExit(running.process.waitFor());
            sink.complete();
        } catch (IOException e) {
            if (!sink.isCancelled()) {
                sink.error(running.timedOut.get() ? running.timeoutError() : new CliExecutionException("读取输出失败", e));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sink.error(new CliExecutionException("等待进程时被中断", e));
        } catch (RuntimeException e) {
            sink.error(e);
        }
    }

    private Running start(List<String> command, Path cwd, String stdin) {
        List<String> args = windows ? quoteForWindows(command) : command;
        ProcessBuilder builder = new ProcessBuilder(args).directory(cwd.toFile());
        builder.environment().putAll(env);
        String name = Path.of(command.getFirst()).getFileName().toString();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new CliExecutionException("启动 " + name + " 失败", e);
        }
        Running running = new Running(name, process, timeout);
        running.watchdog = WATCHDOG.schedule(running::timeout, timeout.toMillis(), TimeUnit.MILLISECONDS);
        running.stderrReader = Thread.ofVirtual().name("cli-stderr").start(running::drainStderr);
        Thread.ofVirtual().name("cli-stdin").start(() -> writeStdin(process, stdin));
        return running;
    }

    private static void writeStdin(Process process, String stdin) {
        try (OutputStream out = process.getOutputStream()) {
            out.write(stdin.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 进程提前退出（比如参数错误）时写不进去，结果以退出码为准
            log.debug("写入 stdin 失败：{}", e.getMessage());
        }
    }

    /** 可执行文件路径由 ProcessBuilder 处理；其余参数按 Windows C 运行库的规则转义。 */
    static List<String> quoteForWindows(List<String> command) {
        List<String> quoted = new ArrayList<>(command.size());
        quoted.add(command.getFirst());
        for (String arg : command.subList(1, command.size())) {
            quoted.add(CliCommandResolver.quoteWindowsArg(arg));
        }
        return quoted;
    }

    /** 杀掉进程及其全部子孙进程，最多等几秒让它们退出（Windows 上进程没退出前删不掉它的工作目录）。 */
    static void killTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
            for (ProcessHandle handle : descendants) {
                handle.onExit().get(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("等待进程退出失败：{}", e.toString());
        }
    }

    private static final class Running {

        private final String name;
        private final Process process;
        private final Duration timeout;
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final Deque<String> stderrTail = new ArrayDeque<>();
        private volatile ScheduledFuture<?> watchdog;
        private volatile Thread stderrReader;

        Running(String name, Process process, Duration timeout) {
            this.name = name;
            this.process = process;
            this.timeout = timeout;
        }

        void timeout() {
            if (process.isAlive()) {
                timedOut.set(true);
                String stderr;
                synchronized (stderrTail) {
                    stderr = stderrTail.isEmpty() ? "（空）" : String.join("\n", stderrTail);
                }
                log.warn("{} 超过 {} 没有结束，杀掉进程树（pid {}），stderr 末尾：{}", name, timeout, process.pid(), stderr);
                killTree(process);
            }
        }

        CliTimeoutException timeoutError() {
            return new CliTimeoutException(name + " 超过 " + timeout + " 没有结束");
        }

        void checkTimedOut() {
            if (timedOut.get()) {
                throw timeoutError();
            }
        }

        void checkExit(int exitCode) {
            checkTimedOut();
            if (exitCode != 0) {
                throw new CliExecutionException(name + " 退出码 " + exitCode + "，stderr 末尾：" + stderrTail());
            }
        }

        void drainStderr() {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderrTail) {
                        stderrTail.addLast(line);
                        if (stderrTail.size() > STDERR_TAIL_LINES) {
                            stderrTail.removeFirst();
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("读取 stderr 结束：{}", e.getMessage());
            }
        }

        String stderrTail() {
            // 进程已经退出，stderr 可能还没读完：最多等 1 秒让读线程把管道读空
            Thread reader = stderrReader;
            if (reader != null) {
                try {
                    reader.join(Duration.ofSeconds(1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (stderrTail) {
                return stderrTail.isEmpty() ? "（空）" : String.join("\n", stderrTail);
            }
        }

        void stop() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> current = watchdog;
            if (current != null) {
                current.cancel(false);
            }
            if (process.isAlive() || process.descendants().findAny().isPresent()) {
                killTree(process);
            }
        }
    }
}
