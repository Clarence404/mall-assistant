package io.github.clarence404.assistant.chat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.assistant.memory.MemoryFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * 第 6 章 ch06-7：选了 jdbc 记忆、数据库却没启动——应用照常启动（连接池和建表都推迟到第一次用到时）；对话照常回答，只是降级成单轮
 * （memory.active=false，带说明）；查看会话 503 MEMORY_UNAVAILABLE。
 *
 * <p>手法：同 {@link FakeModelWebTest}，只是把记忆库指向一个连不上的地址（本机 1 号端口，立刻被拒绝）。每次碰数据库要等连接超时（3 秒）。
 */
@TestPropertySource(
        properties = {
            "assistant.memory.store=jdbc",
            "assistant.memory.jdbc.url=jdbc:postgresql://127.0.0.1:1/mall_assistant"
        })
class JdbcMemoryWithoutDatabaseTest extends FakeModelWebTest {

    @Test
    void chatDegradesAndQueriesAre503() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"conversationId\":\"" + MemoryFixtures.CONVERSATION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").isString())
                .andExpect(jsonPath("$.memory.store").value("jdbc"))
                .andExpect(jsonPath("$.memory.active").value(false))
                .andExpect(jsonPath("$.memory.warning").isString());

        mockMvc.perform(get("/api/conversations/{id}/messages", MemoryFixtures.CONVERSATION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEMORY_UNAVAILABLE"));
    }
}
