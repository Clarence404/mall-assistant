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
 * 集成测试：Testcontainers 起一个真实的 Ollama，拉取小模型后走一遍阻塞与流式接口（第 1 章）和意图识别（第 2 章）。第 1 章引入。
 *
 * <p>Testcontainers 在测试开始前用 Docker 起一个容器（这里是官方 ollama 镜像），测试结束自动销毁；{@code @DynamicPropertySource}
 * 把容器的实际地址填进 {@code spring.ai.ollama.base-url}。这是自动化测试里唯一真的调用模型的一处，用来确认配置、协议和真实输出都对得上。
 *
 * <p>断言故意放得很宽：大模型的输出不确定，同一句话每次回答都可能不同，所以只断言「接口成功、回答里有中文、意图是合法枚举值、置信度在 0 到 1
 * 之间、流里有 intent / token / done 且没有 error」，不断言具体说了什么；唯一断言具体意图的是最明显的「查订单」。温度设成 0（每次都挑最可能的词，
 * 输出更稳定），{@code num-predict=128} 限制每次最多生成 128 个 token（token 是模型处理文本的最小单位，一个汉字大约一到两个 token），让测试跑得快。
 *
 * <p>{@code disabledWithoutDocker = true}：没有 Docker 时自动跳过。模型文件放在宿主机目录（默认 ~/.cache/mall-assistant/ollama，可用环境变量
 * OLLAMA_MODELS_CACHE 指定）并挂进容器的 /root/.ollama，第二次起不再下载；CI 用 actions/cache 缓存这个目录。
 */
@SpringBootTest(properties = {"spring.ai.ollama.chat.num-predict=128", "spring.ai.ollama.chat.temperature=0"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OllamaChatIT {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatIT.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final String IMAGE = "ollama/ollama:0.40.14";
    /** 默认用最小的 qwen2.5:0.5b，可用环境变量 OLLAMA_IT_MODEL 换。 */
    static final String MODEL = System.getenv().getOrDefault("OLLAMA_IT_MODEL", "qwen2.5:0.5b");

    /** static 的 {@code @Container}：整个测试类共用一个容器，所有测试方法跑完才停。 */
    @Container
    static final OllamaContainer OLLAMA =
            new OllamaContainer(IMAGE).withFileSystemBind(modelCacheDir(), "/root/.ollama", BindMode.READ_WRITE);

    @Autowired
    MockMvc mockMvc;

    /** 在容器里执行 {@code ollama pull}：缓存目录里已有模型时很快返回，没有才下载。 */
    @BeforeAll
    static void pullModel() throws Exception {
        ExecResult pull = OLLAMA.execInContainer("ollama", "pull", MODEL);
        log.info("ollama pull {} -> exit {}", MODEL, pull.getExitCode());
        assertThat(pull.getExitCode()).as(pull.getStderr()).isZero();
    }

    /** 容器起来后才知道映射出来的端口，所以地址要在上下文启动前动态注册；同时显式选用 ollama 和要测的模型。 */
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
        assertThat(reply).isNotBlank().containsPattern("\\p{IsHan}"); // 至少有一个汉字：系统提示词要求用中文回答
        assertValidIntent(json.get("intent"));
    }

    /** 第 2 章：真实模型的输出能转成 IntentResult。0.5b 分类常出错，只对最明显的「查订单」断言意图（录制时连跑多次结果一致）。 */
    @Test
    void obviousOrderQuestionIsOrderStatus() throws Exception {
        JsonNode intent = classify("我的订单 1849203385720832512 到哪了？");

        assertValidIntent(intent);
        assertThat(intent.get("intent").asString()).isEqualTo("ORDER_STATUS");
    }

    /** 打招呼、乱码、超出范围的请求：分成什么不重要，重要的是都能得到合法的意图结果，而不是报错。 */
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

    /** 宽松断言：意图是合法的枚举值、置信度在 0 到 1 之间，不管分得对不对。 */
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

    /** 宿主机上的模型缓存目录：环境变量 OLLAMA_MODELS_CACHE，默认 ~/.cache/mall-assistant/ollama，不存在就创建。 */
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
