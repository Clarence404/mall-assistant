package io.github.clarence404.assistant.chat;

import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.memory.MemoryTrace;
import io.github.clarence404.assistant.rag.RagSource;
import io.github.clarence404.assistant.rag.RagTrace;
import io.github.clarence404.assistant.tool.ToolInvocation;
import java.util.List;

/**
 * 阻塞接口的返回：模型的完整回答、先识别出的意图、回答过程中调用过的工具、RAG 回答引用的资料，以及这一轮的记忆情况。
 *
 * <p>所在层：接口层的响应体（第 1 章引入，第 2 章加 intent 字段，第 3 章加 tools 字段，第 5 章加 sources 与 rag 字段，第 6 章加 memory 字段）。由
 * {@link ChatService#chat} 生成，{@link ChatController} 序列化成 JSON。前端把 intent、tools、sources、rag、memory 显示在调试面板里，
 * 并把回答里的 [n] 和 sources 的编号对上。
 *
 * @param reply 给用户看的回答：模型生成的文本，或超出范围时的拒答话术，或 RAG 检索不到时的固定话术
 * @param intent 本次识别出的意图，已经过 {@link IntentResult#sanitized()} 校验
 * @param tools 本次调用过的工具（按调用顺序），没调用时为空列表；不含令牌
 * @param sources RAG 交给模型的资料（编号、标题路径、来源文件、分数、片段），不走 RAG 或没检索到时为空列表
 * @param rag RAG 的调试信息（检索方式、候选数、是否短路、引用校验）；不走 RAG 时为 null
 * @param memory 记忆的调试信息（带了几轮历史、裁掉几轮、追问改写、沿用的订单号）；请求没带会话 ID 时为 null
 */
public record ChatReply(
        String reply,
        IntentResult intent,
        List<ToolInvocation> tools,
        List<RagSource> sources,
        RagTrace rag,
        MemoryTrace memory) {

    /**
     * 不带记忆信息的回答（第 5 章的路径）。
     *
     * @param reply 回答
     * @param intent 意图
     * @param tools 工具调用记录
     * @param sources 引用来源
     * @param rag RAG 调试信息
     */
    public ChatReply(
            String reply, IntentResult intent, List<ToolInvocation> tools, List<RagSource> sources, RagTrace rag) {
        this(reply, intent, tools, sources, rag, null);
    }

    /**
     * 不走 RAG 的回答（第 2、3 章的路径）：没有引用来源。
     *
     * @param reply 回答
     * @param intent 意图
     * @param tools 工具调用记录
     */
    public ChatReply(String reply, IntentResult intent, List<ToolInvocation> tools) {
        this(reply, intent, tools, List.of(), null, null);
    }

    /**
     * 补上记忆的调试信息（第 6 章）。
     *
     * @param trace 记忆调试信息，可以为 null
     * @return 新的回答
     */
    public ChatReply withMemory(MemoryTrace trace) {
        return new ChatReply(reply, intent, tools, sources, rag, trace);
    }
}
