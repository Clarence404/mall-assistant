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
 *
 * <p>架构层：命令行模型。不属于某一章的练习，是第 2 章之后加的可选扩展（{@code claude-cli} / {@code codex-cli} profile 才启用）。
 * 上游：{@link CliChatModel}（阻塞用 {@link #run}，流式用 {@link #lines}）；下游：操作系统进程。和具体是哪个工具无关，参数与输出格式由
 * {@link CliDialect} 负责。
 *
 * <p>为什么 stdin、stdout、stderr 各用一个线程同时处理：进程和我们之间的三根管道都有很小的系统缓冲区。如果只读 stdout 不读
 * stderr，子进程往 stderr 写满缓冲区就会卡住，stdout 也就永远等不到结束；stdin 同理，先写完再读的话，长输入可能和子进程的输出互相等待。
 * 所以 stdin 在单独的线程写完即关闭（关闭 = 告诉子进程「输入结束」），stderr 在单独的线程一直读空，主流程只管 stdout。
 * 这些线程大部分时间阻塞在 IO 上，用虚拟线程很便宜。
 */
public class CliProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(CliProcessRunner.class);

    /** stderr 只保留最后这么多行。 */
    static final int STDERR_TAIL_LINES = 20;

    /** 所有调用共用的一个看门狗线程（守护线程，不阻止 JVM 退出），只负责到点执行 {@link Running#timeout()}。 */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("cli-watchdog").daemon().factory());

    private final Map<String, String> env;
    private final Duration timeout;
    private final boolean windows;

    /**
     * @param env 追加给子进程的环境变量（在继承当前进程环境变量的基础上覆盖）
     * @param timeout 一次调用最长多久，超时杀进程树
     */
    public CliProcessRunner(Map<String, String> env, Duration timeout) {
        this(env, timeout, CliCommandResolver.isWindows());
    }

    /** 测试用：可以指定按 Windows 还是 Linux 的规则处理参数。 */
    CliProcessRunner(Map<String, String> env, Duration timeout, boolean windows) {
        this.env = Map.copyOf(env);
        this.timeout = timeout;
        this.windows = windows;
    }

    /**
     * 阻塞执行，返回完整 stdout。
     *
     * <p>{@code readAllBytes} 一直读到 stdout 关闭（进程退出或被看门狗杀掉）；被杀时退出码非 0，{@code checkExit} 先检查超时标记，
     * 所以超时报的是 {@link CliTimeoutException} 而不是「退出码非 0」。无论成功失败，finally 里都会取消看门狗、清理残留进程。
     *
     * @param command 程序及全部参数
     * @param cwd 工作目录（空临时目录）
     * @param stdin 写进 stdin 的文本
     * @return stdout 全文（UTF-8）
     * @throws CliTimeoutException 超时
     * @throws CliExecutionException 启动失败、退出码非 0、读输出失败或被中断
     */
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
     *
     * <p>{@code Flux.create} 把「回调式」的数据源接进 Reactor：拿到一个 sink，往里 {@code next / complete / error}。读 stdout
     * 是阻塞操作，放在虚拟线程里做，不占用 Reactor 的线程。{@code sink.onDispose} 在序列完成、出错或被取消时都会调用，保证进程不会泄漏。
     *
     * @param command 程序及全部参数
     * @param cwd 工作目录
     * @param stdin 写进 stdin 的文本
     * @return stdout 的逐行序列
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

    /**
     * 在后台线程里把 stdout 逐行推进 sink，读完后检查退出码再完成。下游已取消时立即停止，不再报错（没人收了）。
     * 超时被杀时读管道可能抛 IOException，这时按超时报错。
     */
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

    /**
     * 启动进程并安排好周边：看门狗计时、stderr 读线程、stdin 写线程。
     *
     * @return 运行中的进程及其状态
     * @throws CliExecutionException 进程启动失败（比如程序不存在）
     */
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

    /** 把用户消息按 UTF-8 写进 stdin 后关闭：工具读到 EOF 才知道输入结束，开始生成回答。 */
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

    /**
     * 杀掉进程及其全部子孙进程，最多等几秒让它们退出（Windows 上进程没退出前删不掉它的工作目录）。
     *
     * <p>为什么要杀整棵树：真正干活的往往不是我们直接启动的那个进程（node 脚本还可能再起子进程），只杀父进程会留下孤儿进程继续运行、占着资源。
     * 先取子孙列表再杀，是因为父进程一死，子孙关系就查不到了。
     */
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

    /** 一次运行中的进程及其状态：是否已超时、是否已清理、stderr 末尾若干行、看门狗任务。 */
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

        /** 看门狗到点时执行：进程还活着就记下超时、打日志（带 stderr 末尾帮助排查卡在哪）、杀进程树。 */
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

        /**
         * 进程结束后检查结果：先看是否超时（被杀的进程退出码也非 0，要报超时而不是普通失败），再看退出码。
         *
         * @throws CliTimeoutException 被看门狗杀掉
         * @throws CliExecutionException 退出码非 0，消息带 stderr 末尾
         */
        void checkExit(int exitCode) {
            checkTimedOut();
            if (exitCode != 0) {
                throw new CliExecutionException(name + " 退出码 " + exitCode + "，stderr 末尾：" + stderrTail());
            }
        }

        /** 在后台线程一直读 stderr 直到关闭，只保留最后 {@link #STDERR_TAIL_LINES} 行：既不让管道堵住，也不让内存随输出增长。 */
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

        /** 清理（只执行一次）：取消看门狗；进程或其子孙还活着（比如下游取消了订阅）就杀掉。 */
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
