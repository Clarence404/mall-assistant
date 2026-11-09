package io.github.clarence404.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

/**
 * 第 6 章 TODO ch06-1：提示词窗口——按轮数、按字数裁剪历史，一问一答成对保留或丢掉，摘要不参与裁剪。
 *
 * <p>手法：纯函数，直接拼消息列表断言结果。每轮的文字长度是算好的（「问 n」3 个字、回答用 {@code "答".repeat(k)}），字数预算的边界可以手算。
 */
class HistoryWindowTest {

    private static final Instant AT = MemoryFixtures.NOW;

    /** n 轮对话：第 i 轮是「问 i」+ answerChars 个字的回答。 */
    private static List<Message> dialogue(int turns, int answerChars) {
        List<Message> messages = new ArrayList<>();
        for (int i = 1; i <= turns; i++) {
            messages.add(MemoryMessages.user("问 " + i, AT));
            messages.add(MemoryMessages.assistant("答".repeat(answerChars), AT));
        }
        return messages;
    }

    private static List<String> userTexts(HistoryWindow.Window window) {
        return window.messages().stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .map(MemoryMessages::text)
                .toList();
    }

    @Test
    void emptyHistoryGivesEmptyWindow() {
        HistoryWindow.Window window = HistoryWindow.trim(List.of(), 6, 2000);

        assertThat(window.messages()).isEmpty();
        assertThat(window.keptTurns()).isZero();
        assertThat(window.droppedTurns()).isZero();
        assertThat(window.summary()).isNull();
    }

    /** 轮数窗口：10 轮只带最近 6 轮，丢掉最早的 4 轮；一问一答成对。 */
    @Test
    void keepsOnlyTheLatestTurns() {
        HistoryWindow.Window window = HistoryWindow.trim(dialogue(10, 5), 6, 2000);

        assertThat(window.keptTurns()).isEqualTo(6);
        assertThat(window.droppedTurns()).isEqualTo(4);
        assertThat(window.messages()).hasSize(12);
        assertThat(userTexts(window)).containsExactly("问 5", "问 6", "问 7", "问 8", "问 9", "问 10");
        assertThat(window.messages().getFirst().getMessageType()).isEqualTo(MessageType.USER);
        assertThat(window.messages().getLast().getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(window.chars()).as("「问 5」～「问 9」各 3 个字、「问 10」4 个字，回答各 5 个字").isEqualTo(5 * 3 + 4 + 6 * 5);
    }

    /** 字数预算：每轮 3 + 27 = 30 字，预算 100 字只放得下最近 3 轮。 */
    @Test
    void budgetDropsOldestTurnsFirst() {
        HistoryWindow.Window window = HistoryWindow.trim(dialogue(5, 27), 6, 100);

        assertThat(window.keptTurns()).isEqualTo(3);
        assertThat(window.droppedTurns()).isEqualTo(2);
        assertThat(window.chars()).isEqualTo(90);
        assertThat(userTexts(window)).containsExactly("问 3", "问 4", "问 5");
    }

    /** 预算刚好够：等于预算的不丢。 */
    @Test
    void budgetIsInclusive() {
        HistoryWindow.Window window = HistoryWindow.trim(dialogue(4, 27), 6, 90);

        assertThat(window.keptTurns()).isEqualTo(3);
        assertThat(window.chars()).isEqualTo(90);
    }

    /** 最近的一轮本身就超预算：一轮都不带（宁可不带，也不带半轮）。 */
    @Test
    void singleTurnOverBudgetIsDroppedEntirely() {
        HistoryWindow.Window window = HistoryWindow.trim(dialogue(2, 500), 6, 100);

        assertThat(window.messages()).isEmpty();
        assertThat(window.keptTurns()).isZero();
        assertThat(window.droppedTurns()).isEqualTo(2);
        assertThat(window.chars()).isZero();
    }

    /** 窗口为 0：不带历史。 */
    @Test
    void zeroTurnsMeansNoHistory() {
        HistoryWindow.Window window = HistoryWindow.trim(dialogue(3, 5), 0, 2000);

        assertThat(window.messages()).isEmpty();
        assertThat(window.droppedTurns()).isEqualTo(3);
    }

    /** 摘要（存储里的系统消息）单独拿出来，不算轮数、不算字数，也不出现在 messages 里。 */
    @Test
    void summaryIsSeparatedAndNotCounted() {
        List<Message> stored = new ArrayList<>();
        stored.add(MemoryMessages.summary("用户问过订单 2118… 的物流", AT));
        stored.addAll(dialogue(2, 5));

        HistoryWindow.Window window = HistoryWindow.trim(stored, 6, 2000);

        assertThat(window.summary()).isEqualTo("用户问过订单 2118… 的物流");
        assertThat(window.keptTurns()).isEqualTo(2);
        assertThat(window.chars()).isEqualTo(2 * (3 + 5));
        assertThat(window.messages()).noneMatch(MemoryMessages::isSummary);
    }

    /** 分轮：用户消息开始新的一轮；开头没有提问的助手消息自成一轮。 */
    @Test
    void turnsStartAtUserMessages() {
        List<Message> messages = List.of(
                MemoryMessages.assistant("欢迎光临", AT),
                MemoryMessages.user("问 1", AT),
                MemoryMessages.assistant("答 1", AT),
                MemoryMessages.user("问 2", AT));

        List<List<Message>> turns = HistoryWindow.turns(messages);

        assertThat(turns).hasSize(3);
        assertThat(turns.get(0)).hasSize(1);
        assertThat(turns.get(1)).extracting(MemoryMessages::text).containsExactly("问 1", "答 1");
        assertThat(turns.get(2)).extracting(MemoryMessages::text).containsExactly("问 2");
    }
}
