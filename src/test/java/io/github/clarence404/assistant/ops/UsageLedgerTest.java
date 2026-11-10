package io.github.clarence404.assistant.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 第 9 章 TODO ch09-5：Token 总账——按调用方、意图、会话汇总，一轮只记一次，过了零点（上海时间）清零。
 */
class UsageLedgerTest {

    /** 2026-11-05 10:00（UTC）= 上海时间 18:00。 */
    private ManualClock clock;

    private UsageLedger ledger;

    @BeforeEach
    void setUp() {
        clock = ManualClock.standard();
        ledger = new UsageLedger(UsageProperties.of(1000), clock);
    }

    @Test
    void commitAggregatesByCallerIntentAndConversation() {
        ledger.commit(turn("user:10001", "c1", "POLICY", 300, 50, false));
        ledger.commit(turn("user:10001", "c1", "POLICY", 200, 40, true));
        ledger.commit(turn("ip:127.0.0.1", null, "CHITCHAT", 100, 10, false));

        assertThat(ledger.usedToday("user:10001")).isEqualTo(590);
        assertThat(ledger.usedToday("ip:127.0.0.1")).isEqualTo(110);
        assertThat(ledger.usedToday("user:10002")).isZero();

        UsageLedger.Summary summary = ledger.summary(10);
        assertThat(summary.date()).isEqualTo("2026-11-05");
        assertThat(summary.dailyBudget()).isEqualTo(1000);
        assertThat(summary.total()).isEqualTo(new UsageLedger.Tally(3, 600, 100, 1));
        assertThat(summary.byIntent()).extracting(UsageLedger.Row::key).containsExactly("POLICY", "CHITCHAT");
        assertThat(summary.topCallers().getFirst())
                .isEqualTo(new UsageLedger.Row("user:10001", new UsageLedger.Tally(2, 500, 90, 1)));
        assertThat(summary.topConversations())
                .singleElement()
                .satisfies(r -> assertThat(r.key()).isEqualTo("user:10001/c1"));
    }

    @Test
    void aTurnIsCommittedOnlyOnce() {
        TurnRecord record = turn("user:10001", null, "POLICY", 300, 50, false);

        ledger.commit(record);
        ledger.commit(record);

        assertThat(ledger.usedToday("user:10001")).isEqualTo(350);
    }

    @Test
    void unattributedTurnsAreNotCommitted() {
        ledger.commit(turn(TurnRecord.UNATTRIBUTED, null, "POLICY", 300, 50, false));

        assertThat(ledger.summary(10).total()).isEqualTo(UsageLedger.Tally.ZERO);
    }

    @Test
    void turnWithoutIntentIsGroupedUnderNone() {
        ledger.commit(turn("user:10001", null, null, 100, 0, false));

        assertThat(ledger.summary(10).byIntent())
                .singleElement()
                .satisfies(r -> assertThat(r.key()).isEqualTo(UsageLedger.NO_INTENT));
    }

    /** 过了上海时间的零点就清零：预算按天算。 */
    @Test
    void ledgerRollsOverAtMidnightShanghaiTime() {
        ledger.commit(turn("user:10001", null, "POLICY", 900, 100, false));
        assertThat(ledger.untilReset()).isEqualTo(Duration.ofHours(6));

        clock.advance(Duration.ofHours(5).plusMinutes(59));
        assertThat(ledger.usedToday("user:10001")).isEqualTo(1000);

        clock.advance(Duration.ofMinutes(1));
        assertThat(ledger.usedToday("user:10001")).isZero();
        assertThat(ledger.summary(10).date()).isEqualTo("2026-11-06");
    }

    @Test
    void sameConversationIdOfDifferentCallersIsKeptApart() {
        ledger.commit(turn("user:10001", "c1", "POLICY", 100, 0, false));
        ledger.commit(turn("user:10002", "c1", "POLICY", 50, 0, false));

        assertThat(ledger.summary(10).topConversations())
                .extracting(UsageLedger.Row::key)
                .containsExactly("user:10001/c1", "user:10002/c1");
    }

    /** 一轮：调用方、会话、意图，外加一次模型调用。 */
    static TurnRecord turn(String caller, String conversationId, String intent, int in, int out, boolean estimated) {
        TurnRecord record = new TurnRecord(caller, conversationId);
        record.intent(intent);
        record.add(new ModelCall("rag", null, in, out, estimated, 10));
        return record;
    }
}
