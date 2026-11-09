package io.github.clarence404.assistant.memory;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

/**
 * 提示词窗口：从记忆里取出「这一轮要带给模型的历史」——最近几轮、总字数在预算以内，超出时从最早的一轮开始丢（第 6 章引入）。
 *
 * <p>所在层：业务层的纯函数（不调模型、不碰存储），{@link ConversationMemory#open} 打开会话时调用一次。
 *
 * <p><b>为什么要裁</b>：模型本身没有记忆，每次请求都是独立的；所谓「记得上文」，是我们每次都把历史消息一起发过去。历史越积越长，提示词就越长：
 *
 * <ul>
 *   <li>上下文窗口（模型一次能读的 token 数）有上限，qwen2.5 是 32K，超过就报错或被截断
 *   <li>输入越长越慢、越贵（按 token 计费的接口），本地 CPU 上的小模型尤其明显
 *   <li>小模型「注意力」有限，前面聊过的无关话题会把它带偏；「迷失在中间」：太长的上下文里，中间部分最容易被忽略
 * </ul>
 *
 * <p><b>两道限制</b>：先按轮数（{@code windowTurns}），再按字数（{@code budgetChars}）。只按轮数不够——一轮可能是一句「谢谢」，也可能是
 * 五百字的长回答；只按字数又可能带上十几轮零碎的寒暄。字数是 token 的粗略估算：中文大致一两个字一个 token，精确计数要用模型自己的分词器。
 *
 * <p><b>按「轮」丢，不按「条」丢</b>：只丢一轮里的用户消息、留下助手的回答，模型会看到一句没头没尾的回答；所以一问一答总是一起保留、一起丢。
 * Spring AI 的 {@code MessageWindowChatMemory} 在存储层裁剪时也是这个思路（切点往后挪到下一条用户消息）。
 *
 * <p>系统提示词不在这里面：它不进记忆，每次请求时现渲染，<b>永远保留</b>，不参与裁剪。
 */
public final class HistoryWindow {

    private HistoryWindow() {}

    /**
     * 裁剪结果。
     *
     * @param summary 会话里的早期对话摘要（开了摘要记忆才有），没有时为 null；不参与轮数和字数的裁剪（它自己有长度上限）
     * @param messages 带给模型的历史消息，按时间从早到晚，一问一答成对
     * @param keptTurns 带了几轮
     * @param droppedTurns 因为轮数或字数限制丢掉了几轮（存储里还在，只是这一轮不发给模型）
     * @param chars 带上的历史一共多少个字
     */
    public record Window(String summary, List<Message> messages, int keptTurns, int droppedTurns, int chars) {

        /** 空窗口：新会话或者没有会话。 */
        public static final Window EMPTY = new Window(null, List.of(), 0, 0, 0);

        /** 防御性复制。 */
        public Window {
            messages = List.copyOf(messages);
        }
    }

    /**
     * 按轮数和字数裁剪历史。
     *
     * <p>要求（测试按这个写）：
     *
     * <ol>
     *   <li>系统消息（{@link MemoryMessages#isSummary}）是摘要：取最后一条的文字放进 {@code summary}，不算轮数、不算字数
     *   <li>其余消息按「轮」分组（{@link #turns}）：每条用户消息开始新的一轮，后面的助手消息归进这一轮
     *   <li>只留最后 {@code maxTurns} 轮
     *   <li>再从最早的一轮开始丢，直到剩下的总字数（每条消息文字的长度之和）不超过 {@code budgetChars}；最近的一轮本身就超预算时，也丢掉（一轮都不带）
     *   <li>{@code droppedTurns} = 总轮数 − 留下的轮数；{@code chars} = 留下的总字数
     * </ol>
     *
     * @param stored 记忆里的全部消息，按时间从早到晚
     * @param maxTurns 最多带几轮，0 表示不带历史
     * @param budgetChars 历史最多多少个字
     * @return 裁剪结果
     */
    public static Window trim(List<Message> stored, int maxTurns, int budgetChars) {
        // TODO ch06-1：摘要单独拿出来；其余按「轮」分组（turns），留最后 maxTurns 轮，再从最早一轮丢到总字数不超过 budgetChars（规则见上面的 Javadoc）
        return Window.EMPTY;
    }

    /**
     * 把消息按「轮」分组：遇到用户消息就开始新的一轮，其余消息（助手的回答）归进当前这一轮；开头如果是助手消息（没有对应的提问），自成一轮。
     *
     * @param messages 不含摘要的消息，按时间从早到晚
     * @return 每一轮的消息
     */
    public static List<List<Message>> turns(List<Message> messages) {
        List<List<Message>> turns = new ArrayList<>();
        for (Message message : messages) {
            if (message.getMessageType() == MessageType.USER || turns.isEmpty()) {
                turns.add(new ArrayList<>());
            }
            turns.getLast().add(message);
        }
        return turns;
    }

    /** 一轮的总字数。 */
    static int chars(List<Message> turn) {
        return turn.stream().mapToInt(m -> MemoryMessages.text(m).length()).sum();
    }
}
