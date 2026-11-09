package io.github.clarence404.assistant.memory;

import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.rag.RagSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 多轮对话的录制文件（{@code src/test/resources/recordings/}，第 6 章引入）：
 *
 * <ul>
 *   <li>{@code conversation-cases.json}：要录制的几段对话（每段两三句，是否登录）
 *   <li>{@code conversation-<模型>.json}：真实模型跑完每段对话，每一句记下意图、追问改写、沿用的订单号、带了几轮历史、检索用的问题和检索到的资料、
 *       调用的工具、最终回答；另外把这段对话里<b>每一次</b>模型调用（意图识别、追问改写、回答）的 Prompt 指纹和原样输出都记下来
 * </ul>
 *
 * <p>和第 5 章一样把检索结果也录了下来，回放时不需要向量模型。指纹 = Prompt 里<b>所有</b>消息的类型和文字（第 5 章只看系统消息和最后一条用户消息，
 * 多轮对话里历史也在 Prompt 里，必须都算进去）：改了改写提示词、历史的拼法、窗口裁剪的规则，回放都会报「没有录制」。
 */
final class ConversationRecordings {

    static final String DIR = "recordings";
    static final String CASES = DIR + "/conversation-cases.json";

    static final JsonMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private ConversationRecordings() {}

    /**
     * 一段要录制的对话。
     *
     * @param name 名字
     * @param login 是否带着演示账号的令牌（查订单要登录）
     * @param turns 用户依次说的话
     * @param note 这段想看什么
     */
    record Case(String name, boolean login, List<String> turns, String note) {}

    /**
     * 一句的录制结果。
     *
     * @param message 用户这一句
     * @param intent 意图（含沿用之后的订单号）
     * @param historyTurns 带了几轮历史
     * @param condensedQuestion 追问改写后的问题，没改写时为 null
     * @param carriedOrderNo 沿用的订单号，没沿用时为 null
     * @param retrievalQuery 检索用的问题（走 RAG 时），不走 RAG 时为 null
     * @param sources 检索到、交给模型的资料
     * @param tools 调用过的工具名
     * @param reply 最终回答
     */
    record Turn(
            String message,
            IntentResult intent,
            int historyTurns,
            String condensedQuestion,
            String carriedOrderNo,
            String retrievalQuery,
            List<RagSource> sources,
            List<String> tools,
            String reply) {}

    /**
     * 一次模型调用。
     *
     * @param promptHash Prompt 指纹
     * @param output 模型原样输出
     */
    record Call(String promptHash, String output) {}

    /**
     * 一段对话的录制结果。
     *
     * @param name 名字
     * @param login 是否登录
     * @param turns 每一句
     * @param calls 这段对话里全部的模型调用，按顺序
     */
    record RecordedCase(String name, boolean login, List<Turn> turns, List<Call> calls) {}

    /**
     * 一个对话模型的整份录制。不记录模型服务的地址。
     *
     * @param model 对话模型
     * @param embeddingModel 检索用的向量模型
     * @param recordedAt 录制日期
     * @param cases 每段对话
     */
    record Recording(String model, String embeddingModel, String recordedAt, List<RecordedCase> cases) {}

    static List<Case> cases() {
        try (InputStream in = ConversationRecordings.class.getClassLoader().getResourceAsStream(CASES)) {
            if (in == null) {
                throw new IllegalStateException("classpath 上没有 " + CASES);
            }
            return List.of(JSON.readValue(in, Case[].class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** classpath 上所有 {@code recordings/conversation-*.json}（不含 conversation-cases.json）。 */
    static List<Recording> recordings() {
        try {
            Resource[] resources =
                    new PathMatchingResourcePatternResolver().getResources("classpath:" + DIR + "/conversation-*.json");
            return Arrays.stream(resources)
                    .filter(r -> !"conversation-cases.json".equals(r.getFilename()))
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
                "src",
                "test",
                "resources",
                DIR,
                "conversation-" + recording.model().replace(':', '-') + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writeValueAsString(recording) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    /** Prompt 指纹：每条消息的「类型: 文字」按顺序拼起来，换行统一成 \n，取 SHA-256 的前 8 字节。 */
    static String promptHash(Prompt prompt) {
        String all = prompt.getInstructions().stream()
                .map(m -> m.getMessageType().name() + ": " + m.getText())
                .collect(Collectors.joining("\n---\n"))
                .replace("\r\n", "\n");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(all.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
