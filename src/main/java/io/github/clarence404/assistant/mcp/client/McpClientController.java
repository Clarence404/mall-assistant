package io.github.clarence404.assistant.mcp.client;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调试接口：{@code GET /api/mcp/client/tools}，看看连上的外部 MCP 服务有哪些工具、哪些交给了对话模型（第 8 章引入）。
 *
 * <p>所在层：接口层（调试用）。下游是 {@link ExternalMcpTools#describe}。客户端关闭（默认）时返回空列表。
 */
@RestController
public class McpClientController {

    private final ExternalMcpTools tools;

    /**
     * 创建。
     *
     * @param tools 外部工具
     */
    public McpClientController(ExternalMcpTools tools) {
        this.tools = tools;
    }

    /**
     * 外部工具列表。
     *
     * @return 每个外部工具一行：原名、交给模型时的名字（没交给模型时为 null）、说明（截短）
     */
    @GetMapping("/api/mcp/client/tools")
    public List<ExternalMcpTools.ToolInfo> tools() {
        return tools.describe();
    }
}
