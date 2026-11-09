package io.github.clarence404.assistant.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * 摘要记忆（可选，默认关）：会话超过提示词窗口的轮数时，把最早挤出去的几轮连同旧摘要，让模型压成一段新摘要，原话从记忆里删掉（第 6 章引入）。
 *
 * <p>所在层：业务层。上游是 {@link ConversationMemory#save}（每轮写回之后检查一次）；下游是共享的 {@link ChatClient} 和提示词
 * {@code prompts/memory-summary.st}。
 *
 * <p><b>为什么要它</b>：窗口裁剪是「硬遗忘」，第 7 轮时第 1 轮说的话模型就再也看不到了。摘要把早期对话浓缩成几句话一直带着：用户一开始说的订单号、
 * 已经确认过的结论，聊了很多轮之后模型还知道。
 *
 * <p><b>为什么默认关</b>，代价都在这里：
 *
 * <ul>
 *   <li>每次压缩多一次模型调用，而且发生在回答之后、写回记忆的时候（同步做，阻塞接口和流式的 done 都要等它）；本地小模型一次几秒
 *   <li>摘要会丢细节、会说错（小模型尤其如此），而原话已经删了，错了就再也找不回来；页面刷新后恢复的也只有摘要，看不到早期原话
 *   <li>摘要是模型根据用户的话写的，用户的话里如果有「忽略以上规则」，可能被它带进摘要；所以摘要放进提示词时也圈在标签里（见 {@link MemoryMessages}）
 * </ul>
 *
 * 客服对话大多几轮就结束，窗口裁剪就够了；长对话（导购、复杂售后）才值得开。
 */
@Component
public class ConversationSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummarizer.class);

    /** 摘要要忠实于原话，不需要发挥：温度 0。 */
    static final double TEMPERATURE = 0.0;

    /** 用户消息模板：旧摘要 + 要压缩的几轮对话，用标签圈起来。 */
    static final String USER_TEMPLATE = """
            <要压缩的对话>
            {dialogue}
            </要压缩的对话>
            请写出摘要：""";

    private final ChatClient chatClient;
    private final MemoryProperties properties;
    private final Resource systemPrompt = new ClassPathResource("prompts/memory-summary.st");

    /**
     * 创建。
     *
     * @param chatClient 共享的 ChatClient
     * @param properties {@code assistant.memory.*}（开关、窗口轮数、摘要长度）
     */
    public ConversationSummarizer(ChatClient chatClient, MemoryProperties properties) {
        this.chatClient = chatClient;
        this.properties = properties;
    }

    /**
     * 需要时压缩：对话轮数超过 {@code window-turns} 时，把多出来的最早几轮（连同旧摘要）压成新摘要。
     *
     * @param stored 记忆里的全部消息，按时间从早到晚
     * @param now 当前时间（新摘要的时间戳）
     * @return 压缩后的全部消息（新摘要 + 留下的几轮原话）；没开、不需要压缩或压缩失败时为 null（调用方什么都不改）
     */
    public List<Message> compact(List<Message> stored, Instant now) {
        if (!properties.summary().enabled()) {
            return null;
        }
        String oldSummary = null;
        List<Message> dialogue = new ArrayList<>();
        for (Message message : stored) {
            if (MemoryMessages.isSummary(message)) {
                oldSummary = MemoryMessages.text(message);
            } else {
                dialogue.add(message);
            }
        }
        List<List<Message>> turns = HistoryWindow.turns(dialogue);
        int overflow = turns.size() - properties.windowTurns();
        if (overflow <= 0) {
            return null;
        }
        String text = render(oldSummary, turns.subList(0, overflow));
        try {
            String summary = chatClient
                    .prompt()
                    .system(spec -> spec.text(systemPrompt)
                            .param("maxChars", properties.summary().maxChars()))
                    .user(u -> u.text(USER_TEMPLATE).param("dialogue", text))
                    .options(ChatOptions.builder().temperature(TEMPERATURE))
                    .call()
                    .content();
            String cleaned = summary == null ? "" : summary.strip();
            if (cleaned.isEmpty()) {
                return null;
            }
            if (cleaned.length() > properties.summary().maxChars()) {
                cleaned = cleaned.substring(0, properties.summary().maxChars()) + "…";
            }
            log.info("摘要记忆：压缩了最早的 {} 轮，摘要 {} 字", overflow, cleaned.length());
            List<Message> compacted = new ArrayList<>();
            compacted.add(MemoryMessages.summary(cleaned, now));
            turns.subList(overflow, turns.size()).forEach(compacted::addAll);
            return compacted;
        } catch (RuntimeException e) {
            log.warn("摘要记忆：压缩失败，保留原话：{}", e.toString());
            return null;
        }
    }

    /** 旧摘要 + 要压缩的几轮，写成「用户：…」「助手：…」。 */
    static String render(String oldSummary, List<List<Message>> turns) {
        String dialogue = turns.stream()
                .flatMap(List::stream)
                .map(m -> (m.getMessageType() == MessageType.USER ? "用户：" : "助手：") + MemoryMessages.text(m))
                .collect(Collectors.joining("\n"));
        return oldSummary == null ? dialogue : "之前的摘要：" + oldSummary + "\n" + dialogue;
    }
}
