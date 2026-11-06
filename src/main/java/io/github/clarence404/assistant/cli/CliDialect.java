package io.github.clarence404.assistant.cli;

import java.io.IOException;
import java.util.List;

/**
 * 一种命令行工具的「方言」：怎么拼参数、往 stdin 写什么、怎么解析 stdout。进程的启动、超时与清理由 {@link CliProcessRunner} 负责。
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
     */
    List<String> arguments(CliRequest request, CliWorkspace workspace, List<String> extraArgs) throws IOException;

    /** 写进 stdin 的内容（UTF-8）：用户消息走 stdin，不走命令行参数，免得被 shell 转义、长度受限。 */
    String stdin(CliRequest request);

    /** 解析阻塞调用的完整 stdout；输出不完整或工具报错时抛 {@link CliExecutionException}。 */
    CliReply parseReply(String stdout);

    /** 流式调用：每次调用新建一个解析器，逐行喂给它。 */
    StreamParser streamParser();

    /** 流式输出的逐行解析器。 */
    interface StreamParser {

        /** 解析一行输出，返回其中的增量文本（可能为空）；工具报告失败时抛 {@link CliExecutionException}。 */
        List<String> onLine(String line);

        /** 输出结束（进程正常退出）后调用：可以补发尚未发出的文本，或在没见到结束事件时报错。 */
        List<String> onEnd();
    }
}
