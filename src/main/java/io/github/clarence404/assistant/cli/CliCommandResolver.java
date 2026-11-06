package io.github.clarence404.assistant.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把配置的命令名解析成真正要执行的程序。
 *
 * <p>npm 全局安装的 claude / codex 在 Windows 上是 {@code claude.cmd} 这样的批处理包装，最终执行 {@code node <包>\cli.js}。直接执行
 * .cmd 要经过 cmd.exe：系统提示词里的换行、JSON Schema 里的引号和 {@code < > & |} 都会被 cmd.exe 再解释一遍，很难转义对。所以遇到
 * npm 的 .cmd 包装时，从里面读出脚本路径，改为直接执行 {@code node.exe <脚本>}，完全绕开 cmd.exe。
 *
 * <p>架构层：命令行模型。不属于某一章的练习，是第 2 章之后加的可选扩展（{@code claude-cli} / {@code codex-cli} profile 才启用）。
 * 上游：{@link CliChatModel}（第一次调用时解析一次并缓存）、{@link CliProcessRunner}（用 {@link #quoteWindowsArg} 转义参数）。
 *
 * <p>为什么要自己在 PATH 里找：Java 的 {@link ProcessBuilder} 在 Windows 上不会按 PATHEXT 自动补 {@code .cmd} 扩展名，
 * 直接写 {@code claude} 往往启动失败；而且我们需要拿到 .cmd 的完整路径才能读出里面的脚本位置。
 */
public final class CliCommandResolver {

    /** npm 生成的 .cmd 包装里的脚本路径：{@code "%dp0%\node_modules\@anthropic-ai\claude-code\cli.js"}。 */
    private static final Pattern NPM_SHIM_SCRIPT = Pattern.compile("\"%dp0%\\\\([^\"]+?\\.[cm]?js)\"");

    private CliCommandResolver() {}

    /**
     * 按当前操作系统与环境变量解析。
     *
     * @param command 命令名（如 {@code claude}）或路径
     * @return 要执行的程序及其前置参数
     * @throws CliExecutionException 找不到命令，或 .cmd 不是 npm 生成的包装
     */
    public static List<String> resolve(String command) {
        return resolve(command, isWindows(), System.getenv("PATH"), System.getenv("PATHEXT"));
    }

    /**
     * 解析命令（参数显式传入，方便测试模拟 Windows / Linux）：先找到文件，Windows 下若是 .cmd / .bat 再拆开 npm 包装。
     *
     * @param command 命令名（在 PATH 里找）或路径
     * @param windows 是否按 Windows 规则（PATHEXT、.cmd 包装）解析
     * @param path PATH 环境变量
     * @param pathExt PATHEXT 环境变量（Windows）
     * @return 程序及其前置参数，如 {@code [C:\nodejs\node.exe, C:\nodejs\node_modules\...\cli.js]}
     * @throws CliExecutionException 找不到命令
     */
    public static List<String> resolve(String command, boolean windows, String path, String pathExt) {
        Path executable = find(command, windows, path, pathExt)
                .orElseThrow(() -> new CliExecutionException(
                        "找不到命令行工具 " + command + "：确认已安装并在 PATH 里，或用 assistant.cli.command 指定完整路径"));
        if (windows && isBatchFile(executable)) {
            return unwrapNpmShim(executable, path, pathExt);
        }
        return List.of(executable.toString());
    }

    /**
     * 找到命令对应的文件，规则和 shell 一样：带路径分隔符的直接看这个路径，否则按 PATH 里的目录顺序逐个找，第一个命中的胜出。
     * Linux / macOS 还要求文件可执行；Windows 用扩展名判断能否执行。
     *
     * @return 找到的文件；找不到时为空
     */
    static Optional<Path> find(String command, boolean windows, String path, String pathExt) {
        if (command.contains("/") || command.contains("\\")) {
            return candidates(Path.of(command), windows, pathExt).stream()
                    .filter(Files::isRegularFile)
                    .findFirst();
        }
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String separator = windows ? ";" : File.pathSeparator;
        for (String dir : path.split(Pattern.quote(separator))) {
            if (dir.isBlank()) {
                continue;
            }
            Path base;
            try {
                base = Path.of(dir.strip().replace("\"", "")).resolve(command);
            } catch (RuntimeException e) {
                continue; // PATH 里的非法条目
            }
            Optional<Path> found = candidates(base, windows, pathExt).stream()
                    .filter(p -> Files.isRegularFile(p) && (windows || Files.isExecutable(p)))
                    .findFirst();
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** Windows 下按 PATHEXT 依次尝试 claude.exe、claude.cmd……；不带扩展名的同名文件（给 Git Bash 用的 sh 脚本）不能直接执行，跳过。 */
    private static List<Path> candidates(Path base, boolean windows, String pathExt) {
        if (!windows) {
            return List.of(base);
        }
        String fileName = base.getFileName().toString().toLowerCase(Locale.ROOT);
        List<String> extensions = extensions(pathExt);
        if (extensions.stream().anyMatch(fileName::endsWith)) {
            return List.of(base);
        }
        List<Path> result = new ArrayList<>();
        for (String ext : extensions) {
            result.add(base.resolveSibling(base.getFileName() + ext));
        }
        return result;
    }

    /** PATHEXT 拆成小写扩展名列表；没有 PATHEXT 时用 Windows 的默认值。 */
    private static List<String> extensions(String pathExt) {
        String value = pathExt == null || pathExt.isBlank() ? ".COM;.EXE;.BAT;.CMD" : pathExt;
        List<String> result = new ArrayList<>();
        for (String ext : value.split(";")) {
            if (!ext.isBlank()) {
                result.add(ext.strip().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private static boolean isBatchFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".cmd") || name.endsWith(".bat");
    }

    /**
     * 拆开 npm 的 .cmd 包装：用 {@link #NPM_SHIM_SCRIPT} 从文件内容里找出 {@code "%dp0%\...\cli.js"}（{@code %dp0%} 指 .cmd 所在目录），
     * 换成绝对路径；node 的选择和包装脚本自己的逻辑一致——同目录有 node.exe 就用它，否则用 PATH 里的 node。
     *
     * @param shim .cmd / .bat 文件
     * @return {@code [node 程序, 脚本绝对路径]}
     * @throws CliExecutionException 读不了文件、不是 npm 生成的包装、或包装指向的脚本不存在
     */
    private static List<String> unwrapNpmShim(Path shim, String path, String pathExt) {
        String content;
        try {
            content = Files.readString(shim, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CliExecutionException("读取 " + shim + " 失败", e);
        }
        Matcher matcher = NPM_SHIM_SCRIPT.matcher(content);
        if (!matcher.find()) {
            throw new CliExecutionException(
                    shim + " 不是 npm 生成的包装脚本，不支持直接执行批处理文件：" + "请用 assistant.cli.command 指向 .exe 或 node 脚本");
        }
        Path dir = shim.toAbsolutePath().getParent();
        Path script = dir.resolve(matcher.group(1).replace('\\', '/'));
        if (!Files.isRegularFile(script)) {
            throw new CliExecutionException("npm 包装脚本指向的 " + script + " 不存在");
        }
        Path bundledNode = dir.resolve("node.exe");
        String node = Files.isRegularFile(bundledNode)
                ? bundledNode.toString()
                : find("node", true, path, pathExt).map(Path::toString).orElse("node");
        return List.of(node, script.toString());
    }

    /** 当前是否运行在 Windows 上（按 {@code os.name} 判断）。 */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * 按 Windows C 运行库（node.exe、java.exe 都用它）的规则给一个参数加引号。
     *
     * <p>{@link ProcessBuilder} 在 Windows 上只在参数含空格时简单地包一层引号，不转义参数里的双引号：JSON 这样的参数会被子进程拆坏。
     * 这里自己转义好并包上引号，ProcessBuilder 看到已经带引号的参数就原样传递。不需要引号的参数原样返回。
     *
     * <p>规则：参数里的 {@code "} 写成 {@code \"}；紧挨在 {@code "} 前（或结尾收尾引号前）的反斜杠要加倍，其他位置的反斜杠原样保留
     * （所以 Windows 路径 {@code C:\dir\file} 不受影响）。
     *
     * @param arg 原始参数
     * @return 子进程按 C 运行库规则解析后能还原成 arg 的写法
     */
    public static String quoteWindowsArg(String arg) {
        if (!arg.isEmpty() && arg.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '"' || c == '\n')) {
            return arg;
        }
        StringBuilder quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                backslashes++;
                continue;
            }
            if (c == '"') {
                quoted.append("\\".repeat(backslashes * 2 + 1)).append('"');
            } else {
                quoted.append("\\".repeat(backslashes)).append(c);
            }
            backslashes = 0;
        }
        // 结尾的反斜杠后面紧跟收尾引号，要成对写
        quoted.append("\\".repeat(backslashes * 2)).append('"');
        return quoted.toString();
    }
}
