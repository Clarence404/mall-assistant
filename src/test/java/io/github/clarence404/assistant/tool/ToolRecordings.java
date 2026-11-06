package io.github.clarence404.assistant.tool;

import io.github.clarence404.assistant.chat.ChatProperties;
import io.github.clarence404.assistant.chat.ChatService;
import io.github.clarence404.assistant.intent.IntentService;
import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallApiProperties;
import io.github.clarence404.assistant.prompt.TestPrompts;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 工具调用决策的录制文件（{@code src/test/resources/recordings/}）。第 3 章引入，做法与第 2 章的 {@code IntentRecordings} 相同：
 *
 * <ul>
 *   <li>{@code tools-cases.json}：要录制的用户消息，以及人工标注的「应该调哪个工具、参数是什么」（不该调工具时为 null）
 *   <li>{@code tools-<模型>.json}：用真实模型跑一遍录下的结果。每条消息记下每次模型调用的 Prompt 指纹、模型的原样输出（文字和调用意图），
 *       以及参考实现当时执行了哪些工具、最后回答了什么
 * </ul>
 *
 * <p>录的是「模型的决定」：给它工具清单后，它调不调、调哪个、参数怎么填。这正是工具调用里最不确定、最依赖模型的部分；
 * 录下来回放，就能不联网地验证「代码能正确处理真实模型给出的调用意图」，同时把小模型的命中率留档。
 *
 * <p>Prompt 指纹 = 全部消息的文本（系统、用户、助手的调用意图参数、工具结果）+ 工具定义（名字、说明、参数 Schema）的 SHA-256。
 * 改了系统提示词、工具说明、工具结果的格式，指纹都会变，回放会报「没有录制」，提醒重新录制。
 * 系统提示词里有「今天」，所以录制和回放都用 {@link TestPrompts} 的固定日期。
 */
final class ToolRecordings {

    static final String DIR = "recordings";
    static final String CASES = DIR + "/tools-cases.json";

    static final JsonMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private ToolRecordings() {}

    /**
     * 一条要录制的消息。
     *
     * @param message 用户消息
     * @param expectedTool 应该调用的工具；不该调用时为 null
     * @param expectedArguments 应该传的参数；不该调用时为 null
     */
    record Case(String message, String expectedTool, Map<String, String> expectedArguments) {}

    /** 模型的一次调用意图。 */
    record RecordedToolCall(String id, String name, String arguments) {}

    /** 一次模型调用：Prompt 指纹 → 模型原样输出（文字 + 调用意图）。 */
    record Call(String promptHash, String text, List<RecordedToolCall> toolCalls) {}

    /** 参考实现执行过的一次工具调用（只记名字和参数，结果由假商城决定）。 */
    record Invocation(String name, String arguments) {}

    /** 一条消息的录制结果。 */
    record RecordedCase(
            String message,
            String expectedTool,
            Map<String, String> expectedArguments,
            List<Call> calls,
            List<Invocation> invocations,
            String reply) {}

    /** 一个模型的整份录制。不记录模型服务的地址。 */
    record Recording(String model, String recordedAt, List<RecordedCase> cases) {}

    static List<Case> cases() {
        try (InputStream in = ToolRecordings.class.getClassLoader().getResourceAsStream(CASES)) {
            if (in == null) {
                throw new IllegalStateException("classpath 上没有 " + CASES);
            }
            return List.of(JSON.readValue(in, Case[].class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** classpath 上所有 {@code recordings/tools-*.json}（不含 tools-cases.json）。 */
    static List<Recording> recordings() {
        try {
            Resource[] resources =
                    new PathMatchingResourcePatternResolver().getResources("classpath:" + DIR + "/tools-*.json");
            return Arrays.stream(resources)
                    .filter(r -> !"tools-cases.json".equals(r.getFilename()))
                    .map(r -> {
                        try (InputStream in = r.getInputStream()) {
                            return JSON.readValue(in, Recording.class);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 写到源码目录，录完直接出现在 git diff 里。 */
    static Path write(Recording recording) throws IOException {
        Path file = Path.of(
                "src", "test", "resources", DIR, "tools-" + recording.model().replace(':', '-') + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writeValueAsString(recording) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    /**
     * 录制与回放共用的 ChatService：系统提示词 v3（固定日期）、只开原生工具调用、不开直接查询（要看的就是模型自己的决定）。
     *
     * @param model 真实模型（录制）或回放模型
     * @param mallBaseUrl 假商城地址
     */
    static ChatService chatService(ChatModel model, String mallBaseUrl) {
        ChatClient chatClient = ChatClient.builder(model).build();
        MallTools tools = new MallTools(
                new MallApiClient(new MallApiProperties(mallBaseUrl, Duration.ofSeconds(2), Duration.ofSeconds(5))));
        return new ChatService(
                chatClient,
                new ChatProperties(Duration.ofSeconds(60), 0.6),
                TestPrompts.prompts("v3"),
                new IntentService(chatClient, new ClassPathResource("prompts/intent-v1.st")),
                tools,
                new ToolProperties(true, false));
    }

    /** Prompt 指纹，见类注释。换行统一成 \n。 */
    static String promptHash(Prompt prompt) {
        StringBuilder text = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            text.append(message.getMessageType())
                    .append(':')
                    .append(message.getText())
                    .append('\n');
            if (message instanceof AssistantMessage assistant) {
                assistant
                        .getToolCalls()
                        .forEach(c -> text.append("call:")
                                .append(c.name())
                                .append(c.arguments())
                                .append('\n'));
            }
            if (message instanceof ToolResponseMessage tool) {
                tool.getResponses()
                        .forEach(r -> text.append("result:")
                                .append(r.name())
                                .append(r.responseData())
                                .append('\n'));
            }
        }
        if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null) {
            // 按工具名排序：工具的先后顺序不该影响指纹（MallTools 已经排好序，这里再保险一次）
            List<ToolCallback> sorted = options.getToolCallbacks().stream()
                    .sorted(Comparator.comparing(c -> c.getToolDefinition().name()))
                    .toList();
            for (ToolCallback callback : sorted) {
                text.append("tool:")
                        .append(callback.getToolDefinition().name())
                        .append(callback.getToolDefinition().description())
                        .append(callback.getToolDefinition().inputSchema())
                        .append('\n');
            }
        }
        String normalized = text.toString().replace("\r\n", "\n");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 参数是否与标注一致：按 JSON 比较（键的顺序、空白不影响），值统一按字符串比（模型可能把 20001 写成数字）。 */
    static boolean sameArguments(String argumentsJson, Map<String, String> expected) {
        JsonNode actual = JSON.readTree(argumentsJson);
        if (actual.size() != expected.size()) {
            return false;
        }
        return expected.entrySet().stream()
                .allMatch(e -> actual.has(e.getKey())
                        && e.getValue().equals(actual.get(e.getKey()).asString()));
    }
}
