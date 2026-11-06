package io.github.clarence404.assistant.cli;

import java.io.IOException;
import java.util.List;

/**
 * 一种命令行工具的「方言」：怎么拼参数、往 stdin 写什么、怎么解析 stdout。进程的启动、超时与清理由 {@link CliProcessRunner} 负责。
 *
 * <p>架构层：命令行模型。不属于某一章的练习，是第 2 章之后加的可选扩展（{@code claude-cli} / {@code codex-cli} profile 才启用）。
 * 实现：{@link ClaudeCliDialect}、{@link CodexCliDialect}，由 {@link CliFlavor} 选择；调用方：{@link CliChatModel}。
 *
 * <p>为什么这样拆：「起进程、写 stdin、读 stdout、超时杀进程」对所有工具都一样，只有参数格式和输出格式不同。把不同的部分抽成方言，
 * 通用部分只写一份，测试时也能单独验证每种方言的参数和解析（见 {@code CliDialectTest}）。
 */
public interface CliDialect {

    /** 工具名，用于日志与报错。 */
    String name();

    /**
     * 可执行文件之后的全部参数，安全相关的参数（禁用工具、只读、不落盘……）写死在这里。
     *
     * @param request 本次调用的内容
     * @param workspace 本次调用的临时目录：系统提示词、Schema 等写成文件放在这里
     * @param extraArgs 配置里追加的参数
     * @return 参数列表（不含可执行文件本身）
     * @throws IOException 往 workspace 写输入文件失败
     */
    List<String> arguments(CliRequest request, CliWorkspace workspace, List<String> extraArgs) throws IOException;

    /**
     * 写进 stdin 的内容（UTF-8）：用户消息走 stdin，不走命令行参数，免得被 shell 转义、长度受限。
     *
     * <p>Windows 上命令行总长有上限（约 32K 字符），参数里的引号、换行还要按 C 运行库的规则转义；stdin 是一条字节管道，
     * 按 UTF-8 原样写进去，中文和任意符号都不用转义。
     */
    String stdin(CliRequest request);

    /** 解析阻塞调用的完整 stdout；输出不完整或工具报错时抛 {@link CliExecutionException}。 */
    CliReply parseReply(String stdout);

    /** 流式调用：每次调用新建一个解析器，逐行喂给它（解析器有状态，比如「是否已经输出过文本」，所以不能复用）。 */
    StreamParser streamParser();

    /**
     * 流式输出的逐行解析器：工具的流式输出是 JSON Lines（每行一个 JSON 事件），{@link CliProcessRunner#lines} 每读到一行，
     * {@link CliChatModel#stream} 就交给 {@link #onLine}，返回的每段文本成为推给前端的一个 token 事件。
     */
    interface StreamParser {

        /** 解析一行输出，返回其中的增量文本（可能为空）；工具报告失败时抛 {@link CliExecutionException}。 */
        List<String> onLine(String line);

        /** 输出结束（进程正常退出）后调用：可以补发尚未发出的文本，或在没见到结束事件时报错。 */
        List<String> onEnd();
    }
}
