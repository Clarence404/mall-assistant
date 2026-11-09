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
                .contains("/api/knowledge/stats");
    }
}
