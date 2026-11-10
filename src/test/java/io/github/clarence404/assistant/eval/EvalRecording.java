package io.github.clarence404.assistant.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 评测的录制与回放（第 9 章）：真实模型跑评测时，把每一次对话模型调用的原样输出（文字、工具调用、Token 用量）和每一段文字的向量都记下来；
 * CI 的评测门禁（{@link EvalGateTest}）不连模型，拿录下来的数据回放，跑同一套代码、同一份评测集。
 *
 * <p>和前几章的录制回放（{@code recordings/*.json}）一个思路，只是范围大了：前几章各录各的（意图、工具、RAG、Agent），这里录的是<b>整条链路</b>
 * 一次评测的全部模型调用，外加向量——检索结果决定了 RAG 的提示词，向量对不上，提示词就对不上，回放就找不到录制。
 *
 * <ul>
 *   <li><b>对话模型</b>：按提示词指纹（{@link #hash}：每条消息的角色 + 文字、助手消息里的工具调用参数、工具结果）找录下的输出。同一个指纹录到多次
 *       （同样的问题在两条用例里出现）就按顺序依次给。指纹找不到说明提示词变了（改了提示词模板、检索结果、工具结果的格式），回放模型直接抛异常，
 *       这条用例记为失败，门禁报出「录制里没有这次调用」——要重新录制
 *   <li><b>向量</b>：按文字的 SHA-256 存，值是 float16（半精度）的 Base64：bge-m3 一个向量 1024 维，float32 存一份 5.5 KB，float16 减半。
 *       为了让回放和录制<b>一模一样</b>，录制时也把四舍五入到 float16 的向量交给应用，而不是只在存盘时才截断
 * </ul>
 */
final class EvalRecording {

    static final JsonMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /** 录制文件在源码里的位置（EvalIT 写，EvalGateTest 从 classpath 读）。 */
    static final Path DIR = Path.of("src/test/resources/eval");

    private EvalRecording() {}

    /**
     * 一次对话模型调用的录制。
     *
     * @param hash 提示词指纹
     * @param text 模型输出的文字
     * @param toolCalls 模型要求的工具调用（原生工具调用时）
     * @param model 模型报告的模型名
     * @param inputTokens 输入 Token
     * @param outputTokens 输出 Token
     */
    record Call(
            String hash,
            String text,
            List<ToolCall> toolCalls,
            String model,
            Integer inputTokens,
            Integer outputTokens) {}

    /**
     * 工具调用。
     *
     * @param id 调用 ID
     * @param name 工具名
     * @param arguments JSON 参数
     */
    record ToolCall(String id, String name, String arguments) {}

    /**
     * 一份录制。
     *
     * @param model 对话模型
     * @param embeddingModel 向量模型
     * @param recordedAt 录制日期
     * @param evalSetVersion 评测集版本
     * @param calls 对话模型调用（按时间顺序）
     * @param embeddings 文字的 SHA-256 → float16 向量的 Base64
     */
    record Recording(
            String model,
            String embeddingModel,
            String recordedAt,
            String evalSetVersion,
            List<Call> calls,
            Map<String, String> embeddings) {}

    /** 录制文件名：{@code recording-qwen2.5-1.5b.json}（模型名里的冒号换成连字符）。 */
    static String fileName(String kind, String model) {
        return kind + "-" + model.replace(':', '-').replace('/', '-') + ".json";
    }

    /** 从 classpath 读录制。 */
    static Recording read(String model) {
        return readClasspath(fileName("recording", model), Recording.class);
    }

    /** 从 classpath 读一个 JSON 文件；不存在时为 null。 */
    static <T> T readClasspath(String name, Class<T> type) {
        ClassPathResource resource = new ClassPathResource("eval/" + name);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return JSON.readValue(in, type);
        } catch (IOException e) {
            throw new IllegalStateException("读不了 " + name, e);
        }
    }

    /** 写到源码目录（录制、更新基线）。 */
    static Path writeSource(String name, Object value) throws IOException {
        Path file = DIR.resolve(name);
        Files.createDirectories(file.getParent());
        // 换行统一成 \n：Windows 上 Jackson 的缩进按系统换行符写，git 里存的也是 LF
        Files.writeString(file, JSON.writeValueAsString(value).replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
        return file;
    }

    /**
     * 提示词指纹：每条消息的类型和文字、助手消息里的工具调用（名字 + 参数）、工具结果，SHA-256 取前 16 个十六进制字符。不含选项（温度、模型名）。
     *
     * @param prompt 提示词
     * @return 指纹
     */
    static String hash(Prompt prompt) {
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
        return sha256(text.toString().replace("\r\n", "\n"), 8);
    }

    static String sha256(String text, int bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 包住真实的对话模型：原样转发，记下每次调用。 */
    static final class RecordingChatModel implements ChatModel {

        private final ChatModel delegate;
        final List<Call> calls = new CopyOnWriteArrayList<>();

        RecordingChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse response = delegate.call(prompt);
            AssistantMessage output = response.getResult().getOutput();
            Usage usage = response.getMetadata().getUsage();
            calls.add(new Call(
                    hash(prompt),
                    output.getText(),
                    output.getToolCalls().stream()
                            .map(c -> new ToolCall(c.id(), c.name(), c.arguments()))
                            .toList(),
                    response.getMetadata().getModel(),
                    usage == null ? null : usage.getPromptTokens(),
                    usage == null ? null : usage.getCompletionTokens()));
            return response;
        }

        @Override
        public ChatOptions getOptions() {
            return delegate.getOptions();
        }
    }

    /** 回放：按指纹给出录下的输出；找不到就抛异常（这条用例记为失败，门禁报出要重录）。 */
    static final class ReplayChatModel implements ChatModel {

        private final Map<String, List<Call>> byHash = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> cursors = new ConcurrentHashMap<>();
        final List<String> misses = new CopyOnWriteArrayList<>();

        ReplayChatModel(List<Call> calls) {
            for (Call call : calls) {
                byHash.computeIfAbsent(call.hash(), h -> new ArrayList<>()).add(call);
            }
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            String hash = hash(prompt);
            List<Call> recorded = byHash.get(hash);
            if (recorded == null) {
                misses.add(hash);
                throw new IllegalStateException("录制里没有这次调用（指纹 " + hash + "）：提示词、检索结果或工具结果变了，请重新录制");
            }
            int i = cursors.computeIfAbsent(hash, h -> new AtomicInteger()).getAndIncrement();
            Call call = recorded.get(Math.min(i, recorded.size() - 1));
            AssistantMessage message = AssistantMessage.builder()
                    .content(call.text() == null ? "" : call.text())
                    .toolCalls(
                            call.toolCalls() == null
                                    ? List.of()
                                    : call.toolCalls().stream()
                                            .map(c -> new AssistantMessage.ToolCall(
                                                    c.id(), "function", c.name(), c.arguments()))
                                            .toList())
                    .build();
            ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
            if (call.model() != null) {
                metadata.model(call.model());
            }
            if (call.inputTokens() != null || call.outputTokens() != null) {
                metadata.usage(new DefaultUsage(call.inputTokens(), call.outputTokens()));
            }
            return new ChatResponse(List.of(new Generation(message)), metadata.build());
        }

        /** 和 Ollama 一样声明支持原生结构化输出与原生工具调用（提示词才和录制时一样）。 */
        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().build();
        }
    }

    /** 包住真实的向量模型：向量先四舍五入到 float16 再交给应用，同时记下来。 */
    static final class RecordingEmbeddingModel implements EmbeddingModel {

        private final EmbeddingModel delegate;
        final Map<String, String> vectors = new ConcurrentHashMap<>();

        RecordingEmbeddingModel(EmbeddingModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            EmbeddingResponse response = delegate.call(request);
            List<Embedding> rounded = new ArrayList<>();
            List<String> texts = request.getInstructions();
            for (int i = 0; i < response.getResults().size(); i++) {
                float[] half = roundToHalf(response.getResults().get(i).getOutput());
                vectors.put(sha256(texts.get(i), 32), encode(half));
                rounded.add(new Embedding(half, i));
            }
            return new EmbeddingResponse(rounded);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }
    }

    /** 回放向量：找不到就抛异常。 */
    static final class ReplayEmbeddingModel implements EmbeddingModel {

        private final Map<String, String> vectors;
        private final int dimensions;

        ReplayEmbeddingModel(Map<String, String> vectors) {
            this.vectors = vectors;
            this.dimensions = vectors.values().stream()
                    .findFirst()
                    .map(v -> decode(v).length)
                    .orElse(0);
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            List<String> texts = request.getInstructions();
            for (int i = 0; i < texts.size(); i++) {
                String encoded = vectors.get(sha256(texts.get(i), 32));
                if (encoded == null) {
                    throw new IllegalStateException("录制里没有这段文字的向量（知识文档或问题变了），请重新录制："
                            + texts.get(i)
                                    .substring(0, Math.min(30, texts.get(i).length())));
                }
                embeddings.add(new Embedding(decode(encoded), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public int dimensions() {
            return dimensions;
        }
    }

    /** 每个分量四舍五入到 float16 能表示的值（JDK 20 起有 {@code Float.floatToFloat16}）。 */
    static float[] roundToHalf(float[] vector) {
        float[] half = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            half[i] = Float.float16ToFloat(Float.floatToFloat16(vector[i]));
        }
        return half;
    }

    /** float16 小端序 → Base64。 */
    static String encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putShort(Float.floatToFloat16(v));
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    static float[] decode(String encoded) {
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encoded)).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[buffer.capacity() / 2];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = Float.float16ToFloat(buffer.getShort());
        }
        return vector;
    }
}
