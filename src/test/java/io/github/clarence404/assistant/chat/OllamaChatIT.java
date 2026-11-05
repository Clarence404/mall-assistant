package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.assistant.intent.Intent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 集成测试：Testcontainers 起一个真实的 Ollama，拉取小模型后走一遍阻塞与流式接口（第 1 章）和意图识别（第 2 章）。
 *
 * <p>没有 Docker 时自动跳过。模型文件放在宿主机目录（默认 ~/.cache/mall-assistant/ollama，可用环境变量
 * OLLAMA_MODELS_CACHE 指定）并挂进容器的 /root/.ollama，第二次起不再下载；CI 用 actions/cache 缓存这个目录。
 */
@SpringBootTest(properties = {"spring.ai.ollama.chat.num-predict=128", "spring.ai.ollama.chat.temperature=0"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OllamaChatIT {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatIT.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

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

        JsonNode json = JSON.readTree(body);
        String reply = json.get("reply").asString();
        log.info("模型回答：{}，意图：{}", reply, json.get("intent"));
        assertThat(reply).isNotBlank().containsPattern("\\p{IsHan}");
        assertValidIntent(json.get("intent"));
    }

    /** 第 2 章：真实模型的输出能转成 IntentResult。0.5b 分类常出错，只对最明显的「查订单」断言意图（录制时连跑多次结果一致）。 */
    @Test
    void obviousOrderQuestionIsOrderStatus() throws Exception {
        JsonNode intent = classify("我的订单 1849203385720832512 到哪了？");

        assertValidIntent(intent);
        assertThat(intent.get("intent").asString()).isEqualTo("ORDER_STATUS");
    }

    @Test
    void anyMessageYieldsValidIntentInsteadOfError() throws Exception {
        for (String message : List.of("你好", "asdf qwer zxcv", "帮我写一段 Python 冒泡排序")) {
            assertValidIntent(classify(message));
        }
    }

    private JsonNode classify(String message) throws Exception {
        String body = mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new ChatRequest(message))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        log.info("意图识别：{} -> {}", message, body);
        return JSON.readTree(body);
    }

    private static void assertValidIntent(JsonNode intent) {
        assertThat(intent).isNotNull();
        assertThat(Arrays.stream(Intent.values()).map(Intent::name))
                .contains(intent.get("intent").asString());
        assertThat(intent.get("confidence").asDouble()).isBetween(0.0, 1.0);
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
        assertThat(body).contains("event:intent").contains("event:token").doesNotContain("event:error");
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
