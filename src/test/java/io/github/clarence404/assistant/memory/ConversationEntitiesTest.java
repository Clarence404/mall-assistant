package io.github.clarence404.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.intent.Intent;
import io.github.clarence404.assistant.intent.IntentResult;
import io.github.clarence404.assistant.mall.StubMallApi;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

/**
 * 第 6 章 TODO ch06-4：指代沿用——「它什么时候下的单」从最近几轮的历史里沿用订单号，「这个多少钱」沿用 SKU；要列表时不沿用，太久以前的不沿用。
 */
class ConversationEntitiesTest {

    private static final Instant AT = MemoryFixtures.NOW;
    private static final String ORDER = StubMallApi.ORDER_NO;
    private static final String OLDER_ORDER = StubMallApi.COMPLETED_ORDER_NO;

    private static IntentResult orderStatus() {
        return new IntentResult(Intent.ORDER_STATUS, 0.9, null, null);
    }

    private static List<Message> history(String... texts) {
        List<Message> messages = new java.util.ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            messages.add(i % 2 == 0 ? MemoryMessages.user(texts[i], AT) : MemoryMessages.assistant(texts[i], AT));
        }
        return messages;
    }

    /** 上一句提到了订单号，这一句没提：补上，记下沿用了哪个。 */
    @Test
    void orderNoIsCarriedFromHistory() {
        List<Message> history = history("订单 " + ORDER + " 到哪了", "这笔订单待发货，商家会尽快发出。");

        ConversationEntities.Carried carried = ConversationEntities.carryOver(orderStatus(), "它什么时候下的单", history, 3);

        assertThat(carried.intent().orderNo()).isEqualTo(ORDER);
        assertThat(carried.intent().intent()).isEqualTo(Intent.ORDER_STATUS);
        assertThat(carried.orderNo()).isEqualTo(ORDER);
        assertThat(carried.skuId()).isNull();
    }

    /** 这一句自己带了订单号：用它的，不沿用。 */
    @Test
    void explicitOrderNoWins() {
        List<Message> history = history("订单 " + OLDER_ORDER + " 到哪了", "已完成。");
        IntentResult intent = new IntentResult(Intent.ORDER_STATUS, 0.9, ORDER, null);

        ConversationEntities.Carried carried = ConversationEntities.carryOver(intent, "那 " + ORDER + " 呢", history, 3);

        assertThat(carried.intent().orderNo()).isEqualTo(ORDER);
        assertThat(carried.orderNo()).isNull();
    }

    /** 最近提到的那个优先：从最新的一轮往前找。 */
    @Test
    void latestMentionWins() {
        List<Message> history = history("订单 " + OLDER_ORDER + " 到哪了", "已完成。", "那 " + ORDER + " 呢", "待发货。");

        assertThat(ConversationEntities.carryOver(orderStatus(), "它什么时候下的单", history, 3)
                        .orderNo())
                .isEqualTo(ORDER);
    }

    /** 助手列出的订单也算，同一条消息里取最先出现的（列表从新到旧，第一笔是最新的）。 */
    @Test
    void firstOrderInAssistantListIsUsed() {
        List<Message> history = history("我有哪些订单", "最近两笔：" + ORDER + "（待发货）、" + OLDER_ORDER + "（已完成）");

        assertThat(ConversationEntities.carryOver(orderStatus(), "它到哪了", history, 3)
                        .orderNo())
                .isEqualTo(ORDER);
    }

    /** 明说要看列表：不沿用，还是走第 3 章的「列出最近的订单」。 */
    @Test
    void listRequestIsNotCarried() {
        List<Message> history = history("订单 " + ORDER + " 到哪了", "待发货。");

        ConversationEntities.Carried carried = ConversationEntities.carryOver(orderStatus(), "我还有哪些订单", history, 3);

        assertThat(carried.intent().orderNo()).isNull();
        assertThat(carried.orderNo()).isNull();
    }

    /** 只看最近 N 轮：订单号在 4 轮之前提到的，不沿用（话题多半已经换了）。 */
    @Test
    void onlyRecentTurnsAreSearched() {
        List<Message> history =
                history("订单 " + ORDER + " 到哪了", "待发货。", "积分多久过期", "12 个月。", "发票怎么开", "在订单详情里申请。", "运费多少", "满 99 包邮。");

        assertThat(ConversationEntities.carryOver(orderStatus(), "它什么时候下的单", history, 3)
                        .orderNo())
                .isNull();
        assertThat(ConversationEntities.carryOver(orderStatus(), "它什么时候下的单", history, 4)
                        .orderNo())
                .isEqualTo(ORDER);
    }

    /** 问商品没带 SKU：沿用最近提到的「SKU 20001」；光是一串短数字（「7 天」「20 元」）不算 SKU。 */
    @Test
    void skuIsCarriedOnlyWhenMarked() {
        IntentResult product = new IntentResult(Intent.PRODUCT_INFO, 0.9, null, null);

        assertThat(ConversationEntities.carryOver(product, "这个还有货吗", history("SKU 20001 多少钱", "39.9 元。"), 3)
                        .skuId())
                .isEqualTo("20001");
        assertThat(ConversationEntities.carryOver(product, "这个还有货吗", history("运费 20 元吗", "满 99 包邮，7 天送达。"), 3)
                        .skuId())
                .isNull();
    }

    /** 模型给的号要核对：原话和历史里都没出现过的（抄错了），扔掉，再按沿用规则补。 */
    @Test
    void numbersNotSeenAnywhereAreDropped() {
        List<Message> history = history("订单 " + ORDER + " 到哪了", "待发货。");
        IntentResult miscopied = new IntentResult(Intent.ORDER_STATUS, 0.9, StubMallApi.OTHER_USERS_ORDER_NO, "99999");

        ConversationEntities.Carried carried = ConversationEntities.carryOver(miscopied, "它什么时候下的单", history, 3);

        assertThat(carried.intent().orderNo()).isEqualTo(ORDER);
        assertThat(carried.intent().skuId()).isNull();
        assertThat(carried.orderNo()).isEqualTo(ORDER);
    }

    /** 其他意图不沿用。 */
    @Test
    void otherIntentsAreUntouched() {
        IntentResult policy = new IntentResult(Intent.POLICY, 0.9, null, null);
        List<Message> history = history("订单 " + ORDER + " 到哪了", "待发货。");

        ConversationEntities.Carried carried = ConversationEntities.carryOver(policy, "那运费谁出", history, 3);

        assertThat(carried.intent()).isEqualTo(policy);
        assertThat(carried.orderNo()).isNull();
    }

    /** 订单号要整串是 19 / 23 位数字：手机号（11 位）、更长的数字串里截出来的不算。 */
    @Test
    void orderNoFormatIsStrict() {
        List<Message> history = history("我的手机号是 13800000000，单号 1234567890123456789012345", "好的。");

        assertThat(ConversationEntities.carryOver(orderStatus(), "它到哪了", history, 3)
                        .orderNo())
                .isNull();
    }
}
