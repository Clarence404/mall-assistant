package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 冒烟测试：应用能启动、首页能打开（start 标签下也应通过）。第 1 章引入。
 *
 * <p>只检查静态页面被正确提供、关键文字与接口地址在页面里，不执行页面里的 JavaScript。
 */
class IndexPageTest extends FakeModelWebTest {

    @Test
    void rootForwardsToChatPage() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
    }

    @Test
    void chatPageIsServed() throws Exception {
        String html = mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("示例商城智能客服")
                .contains("<title>智能客服 · 示例商城</title>")
                .contains("rel=\"icon\" href=\"data:image/svg+xml,")
                .contains("/api/chat/stream")
                .contains("调试面板")
                .contains("dbgIntent")
                // 第 3 章：登录框（经 /api/mall/login 登录）、提问时带 Authorization、调试面板列出工具调用
                .contains("/api/mall/login")
                .contains("Authorization")
                .contains("dbgTools")
                // 第 4 章：知识库调试区（入库、检索、概况）
                .contains("/api/knowledge/search")
                .contains("/api/knowledge/ingest")
                .contains("/api/knowledge/stats")
                // 第 5 章：调试面板的引用来源、流式的 source 事件、回答里可点的引用编号
                .contains("dbgSources")
                .contains("'source'")
                .contains("renderCitations")
                // 第 6 章：会话 ID（localStorage）、请求体带 conversationId、恢复与清空历史、新对话、调试面板的记忆一栏与 memory 事件
                .contains("localStorage")
                .contains("conversationId")
                .contains("/api/conversations/")
                .contains("新对话")
                .contains("dbgMemory")
                .contains("'memory'")
                // 第 7 章：调试面板的 Agent 步骤（step 事件）、确认卡片（confirm 事件与 confirmation 字段）、点按钮调确认接口
                .contains("dbgAgent")
                .contains("'step'")
                .contains("'confirm'")
                .contains("body.confirmation")
                .contains("/api/agent/confirmations/")
                .contains("confirm-card")
                // 第 8 章：打开 MCP 工具给的确认链接 /?confirm=<ID>，GET 取卡片，点按钮时不带会话 ID
                .contains("get('confirm')")
                .contains("showMcpConfirmation")
                .contains("fromMcp ? null : conversationId")
                // 第 9 章：调试面板的 Token 用量（阻塞在 usage 字段、流式在 done 事件里）、响应头里的 traceId、被注入过滤挡下的资料
                .contains("dbgUsage")
                .contains("showUsage")
                .contains("X-Trace-Id")
                .contains("addBlocked")
                .contains("'blocked'");
    }
}
