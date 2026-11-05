package io.github.clarence404.assistant.intent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 意图识别的录制文件（{@code src/test/resources/recordings/}）：
 *
 * <ul>
 *   <li>{@code intent-cases.json}：要录制的用户消息，以及人工标注的「应该是什么意图」
 *   <li>{@code intent-<模型>.json}：用真实模型跑一遍录下的结果，每条消息记下每次模型调用的 Prompt 指纹和模型原样输出，以及当时解析出的
 *       {@link IntentResult}
 * </ul>
 *
 * <p>Prompt 指纹是「系统消息 + 用户消息」的 SHA-256：提示词、用户消息模板、输出格式（{@link IntentResult} 的字段）任何一处变了，指纹就对不上，
 * 回放会直接报「没有录制」，提醒你重新录制，而不是拿旧输出假装通过。
 */
final class IntentRecordings {

    static final String DIR = "recordings";
    static final String CASES = DIR + "/intent-cases.json";

    static final JsonMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private IntentRecordings() {}

    /** 一条要录制的用户消息。expected 是人工标注，只用来统计准确率，不参与断言。 */
    record Case(String message, Intent expected) {}

    /** 一次模型调用：Prompt 指纹 → 模型原样输出。 */
    record Call(String promptHash, String output) {}

    /** 一条消息的录制结果：经过了几次模型调用（重试时是 2 次），以及参考实现当时解析出的结果。 */
    record RecordedCase(String message, Intent expected, List<Call> calls, IntentResult result) {}

    /** 一个模型的整份录制。不记录模型服务的地址。 */
    record Recording(String model, String recordedAt, List<RecordedCase> cases) {}

    static List<Case> cases() {
        return List.of(read(CASES, Case[].class));
    }

    /** classpath 上所有 {@code recordings/intent-*.json}（不含 intent-cases.json）。 */
    static List<Recording> recordings() {
        try {
            Resource[] resources =
                    new PathMatchingResourcePatternResolver().getResources("classpath:" + DIR + "/intent-*.json");
            return java.util.Arrays.stream(resources)
                    .filter(r -> !"intent-cases.json".equals(r.getFilename()))
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

    /** 写到源码目录（不是 target/），录完直接出现在 git diff 里。 */
    static Path write(Recording recording) throws IOException {
        Path file = Path.of(
                "src", "test", "resources", DIR, "intent-" + recording.model().replace(':', '-') + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writeValueAsString(recording) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    /** Prompt 指纹：系统消息 + 用户消息，换行统一成 \n（Spring AI 拼格式说明用的是系统换行符，Windows 与 Linux 不同）。 */
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

    private static <T> T read(String path, Class<T> type) {
        try (InputStream in = IntentRecordings.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("classpath 上没有 " + path);
            }
            return JSON.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
