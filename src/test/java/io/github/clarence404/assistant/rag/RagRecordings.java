package io.github.clarence404.assistant.rag;

import io.github.clarence404.assistant.intent.Intent;
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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * RAG 回答的录制文件（{@code src/test/resources/recordings/}，第 5 章引入）：
 *
 * <ul>
 *   <li>{@code rag-cases.json}：要录制的问题和意图
 *   <li>{@code rag-<模型>.json}：真实的向量模型检索、真实的对话模型回答，每条问题记下交给模型的引用来源、Prompt 指纹、模型原样输出，
 *       以及参考实现引用校验之后的回答
 * </ul>
 *
 * <p>和第 2、3 章的录制不同，这里把<b>检索结果（引用来源）也录了下来</b>：回放时不需要向量模型，直接拿录下的来源拼提示词，
 * 拼出来的 Prompt 指纹和录制时一致，才能取出模型当时的原话。指纹 = 系统消息 + 用户消息（含编号资料）的文本：改了 {@code rag-v1.st}、
 * {@code RagService#CONTEXT_TEMPLATE} 或资料的拼法，回放会报「没有录制」，提醒重新录制。
 *
 * <p>为什么要录：引用校验（{@link CitationChecker}）处理的是真实模型会写出什么——全角括号、[1, 2]、不存在的编号、一个都不标……这些
 * 靠手写的假回答想不全。
 */
final class RagRecordings {

    static final String DIR = "recordings";
    static final String CASES = DIR + "/rag-cases.json";

    static final JsonMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private RagRecordings() {}

    /**
     * 一条要录制的问题。
     *
     * @param message 用户的问题
     * @param intent 意图（录制时固定，不调意图识别）
     * @param note 这条想看什么（人看的说明）
     */
    record Case(String message, Intent intent, String note) {}

    /**
     * 一条问题的录制结果。
     *
     * @param message 问题
     * @param intent 意图
     * @param note 说明
     * @param sources 交给模型的引用来源；检索不到时为空列表（这时没有调用模型）
     * @param promptHash 回答调用的 Prompt 指纹；短路时为 null
     * @param output 模型原样输出；短路时为 null
     * @param reply 参考实现最终给用户的回答（引用校验之后）
     * @param cited 有效的引用编号
     * @param invalid 被去掉的编号
     * @param uncited 是否一个编号都没引用；短路时为 null
     */
    record RecordedCase(
            String message,
            Intent intent,
            String note,
            List<RagSource> sources,
            String promptHash,
            String output,
            String reply,
            List<Integer> cited,
            List<Integer> invalid,
            Boolean uncited) {}

    /**
     * 一个对话模型的整份录制。不记录模型服务的地址。
     *
     * @param model 对话模型
     * @param embeddingModel 检索用的向量模型
     * @param recordedAt 录制日期
     * @param cases 每条问题的结果
     */
    record Recording(String model, String embeddingModel, String recordedAt, List<RecordedCase> cases) {}

    static List<Case> cases() {
        try (InputStream in = RagRecordings.class.getClassLoader().getResourceAsStream(CASES)) {
            if (in == null) {
                throw new IllegalStateException("classpath 上没有 " + CASES);
            }
            return List.of(JSON.readValue(in, Case[].class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** classpath 上所有 {@code recordings/rag-*.json}（不含 rag-cases.json）。 */
    static List<Recording> recordings() {
        try {
            Resource[] resources =
                    new PathMatchingResourcePatternResolver().getResources("classpath:" + DIR + "/rag-*.json");
            return Arrays.stream(resources)
                    .filter(r -> !"rag-cases.json".equals(r.getFilename()))
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
                "src", "test", "resources", DIR, "rag-" + recording.model().replace(':', '-') + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writeValueAsString(recording) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    /** Prompt 指纹：系统消息 + 用户消息，换行统一成 \n（模板引擎按系统换行符输出，Windows 与 Linux 不同）。 */
    static String promptHash(Prompt prompt) {
        String system = prompt.getSystemMessage().getText();
        String user = prompt.getUserMessage().getText();
        String normalized = (system + "\n\n" + user).replace("\r\n", "\n");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
