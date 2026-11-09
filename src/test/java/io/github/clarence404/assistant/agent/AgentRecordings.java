package io.github.clarence404.assistant.agent;

import io.github.clarence404.assistant.chat.ChatReply;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.mall.StubMallApi;
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
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agent 决策的录制文件（{@code src/test/resources/recordings/}，第 7 章引入）：
 *
 * <ul>
 *   <li>{@code agent-cases.json}：要录制的几件售后的事（一句话，登录状态）
 *   <li>{@code agent-<模型>.json}：真实模型把每件事从头办到尾（意图识别 → Agent 每一步 → 停下），记下意图、每一步（动作、类型、参数、结果）、为什么停、
 *       提出的待确认操作、最终回答；另外把<b>每一次</b>模型调用的 Prompt 指纹和原样输出、每一次查规则检索到的资料都记下来
 * </ul>
 *
 * <p>指纹 = Prompt 里所有消息的类型和文字（同第 6 章）：改了 Agent 的提示词、办事记录的拼法、工具结果的格式，回放都会报「没有录制」，要重新录。
 */
final class AgentRecordings {

    static final String DIR = "recordings";
    static final String CASES = DIR + "/agent-cases.json";

    static final JsonMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private AgentRecordings() {}

    /**
     * 一件要录制的事。
     *
     * @param name 名字
     * @param message 用户的话
     * @param expected 期望 Agent 最后提出的写操作（工具名）；只是说明，不做断言（小模型办不对也照样录下来）
     * @param note 这件事想看什么
     */
    record Case(String name, String message, String expected, String note) {}

    /**
     * 一步的录制结果（不含耗时）。
     *
     * @param action 工具名
     * @param kind 类型
     * @param arguments 参数
     * @param observation 结果
     */
    record Step(String action, AgentStep.Kind kind, String arguments, String observation) {}

    /**
     * 提出的待确认操作（不含 ID 和过期时间）。
     *
     * @param action 写操作
     * @param orderNo 订单号
     * @param params 参数
     * @param summary 说明
     */
    record Proposal(String action, String orderNo, Map<String, String> params, String summary) {}

    /**
     * 一次模型调用。
     *
     * @param promptHash Prompt 指纹
     * @param output 模型原样输出
     */
    record Call(String promptHash, String output) {}

    /**
     * 一件事的录制结果。
     *
     * @param name 名字
     * @param message 用户的话
     * @param intent 意图
     * @param steps 每一步
     * @param stopReason 为什么停（不走 Agent 时为 null）
     * @param proposal 提出的写操作，没有时为 null
     * @param reply 最终回答
     * @param elapsedMs 录制时的总耗时（只作参考，回放不比较）
     * @param retrievals 查规则时每个问题检索到的资料（回放时不需要向量模型）
     * @param calls 全部模型调用，按顺序
     */
    record RecordedCase(
            String name,
            String message,
            IntentResult intent,
            List<Step> steps,
            AgentTrace.StopReason stopReason,
            Proposal proposal,
            String reply,
            long elapsedMs,
            Map<String, List<RagSource>> retrievals,
            List<Call> calls) {}

    /**
     * 一个对话模型的整份录制。不记录模型服务的地址。
     *
     * @param model 对话模型
     * @param embeddingModel 查规则用的向量模型
     * @param recordedAt 录制日期
     * @param cases 每件事
     */
    record Recording(String model, String embeddingModel, String recordedAt, List<RecordedCase> cases) {}

    /** 把一次回答转成录制的形式。 */
    static RecordedCase recorded(
            Case c, ChatReply reply, long elapsedMs, Map<String, List<RagSource>> retrievals, List<Call> calls) {
        AgentTrace trace = reply.agent();
        List<Step> steps = trace == null
                ? List.of()
                : trace.steps().stream()
                        .map(s -> new Step(s.action(), s.kind(), s.arguments(), s.observation()))
                        .toList();
        PendingConfirmation p = reply.confirmation();
        return new RecordedCase(
                c.name(),
                c.message(),
                reply.intent(),
                steps,
                trace == null ? null : trace.stopReason(),
                p == null ? null : new Proposal(p.action(), p.orderNo(), p.params(), p.summary()),
                reply.reply(),
                elapsedMs,
                retrievals,
                calls);
    }

    /** 录制和回放都用演示账号登录。 */
    static String token() {
        return StubMallApi.TOKEN;
    }

    static List<Case> cases() {
        try (InputStream in = AgentRecordings.class.getClassLoader().getResourceAsStream(CASES)) {
            if (in == null) {
                throw new IllegalStateException("classpath 上没有 " + CASES);
            }
            return List.of(JSON.readValue(in, Case[].class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** classpath 上所有 {@code recordings/agent-*.json}（不含 agent-cases.json）。 */
    static List<Recording> recordings() {
        try {
            Resource[] resources =
                    new PathMatchingResourcePatternResolver().getResources("classpath:" + DIR + "/agent-*.json");
            return Arrays.stream(resources)
                    .filter(r -> !"agent-cases.json".equals(r.getFilename()))
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
                "src", "test", "resources", DIR, "agent-" + recording.model().replace(':', '-') + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writeValueAsString(recording) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    /** Prompt 指纹：每条消息的「类型: 文字」按顺序拼起来，换行统一成 \n，取 SHA-256 的前 8 字节（同第 6 章）。 */
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
