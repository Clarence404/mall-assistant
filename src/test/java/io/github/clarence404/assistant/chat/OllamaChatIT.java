package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.ollama.OllamaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 第 1 章集成测试：Testcontainers 起一个真实的 Ollama，拉取小模型后走一遍阻塞与流式接口。
 *
 * <p>没有 Docker 时自动跳过。模型文件放在宿主机目录（默认 ~/.cache/mall-assistant/ollama，可用环境变量
 * OLLAMA_MODELS_CACHE 指定）并挂进容器的 /root/.ollama，第二次起不再下载；CI 用 actions/cache 缓存这个目录。
 */
@SpringBootTest(properties = {"spring.ai.ollama.chat.num-predict=128", "spring.ai.ollama.chat.temperature=0"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OllamaChatIT {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatIT.class);

    static final String IMAGE = "ollama/ollama:0.40.14";
    static final String MODEL = System.getenv().getOrDefault("OLLAMA_IT_MODEL", "qwen2.5:0.5b");

    @Container
    static final OllamaContainer OLLAMA =
            new OllamaContainer(IMAGE).withFileSystemBind(modelCacheDir(), "/root/.ollama", BindMode.READ_WRITE);

    @Autowired
    MockMvc mockMvc;

    @BeforeAll
    static void pullModel() throws Exception {
        ExecResult pull = OLLAMA.execInContainer("ollama", "pull", MODEL);
        log.info("ollama pull {} -> exit {}", MODEL, pull.getExitCode());
        assertThat(pull.getExitCode()).as(pull.getStderr()).isZero();
    }

    @DynamicPropertySource
    static void ollamaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.model.chat", () -> "ollama");
        registry.add("spring.ai.ollama.base-url", OLLAMA::getEndpoint);
        registry.add("spring.ai.ollama.chat.model", () -> MODEL);
    }

    @Test
    void blockingReplyIsChinese() throws Exception {
        String body = mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好，请用一句话介绍一下你自己。\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        String reply = JsonMapper.builder().build().readTree(body).get("reply").asString();
        log.info("模型回答：{}", reply);
        assertThat(reply).isNotBlank().containsPattern("\\p{IsHan}");
    }

    @Test
    void streamCompletesWithTokensAndDone() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"怎么申请退货？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(120_000);

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        log.info("流式响应：{}", body);
        assertThat(body).contains("event:token").doesNotContain("event:error");
        assertThat(body.strip()).contains("event:done");
        assertThat(body).containsPattern("\\p{IsHan}");
    }

    private static String modelCacheDir() {
        String configured = System.getenv("OLLAMA_MODELS_CACHE");
        Path dir = configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".cache", "mall-assistant", "ollama");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建模型缓存目录 " + dir, e);
        }
        return dir.toAbsolutePath().toString();
    }
}
