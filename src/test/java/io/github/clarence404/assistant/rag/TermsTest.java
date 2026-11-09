package io.github.clarence404.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 第 5 章：关键词检索的切词（已经给好，start 时就是绿的）。看懂它再写 {@link KeywordIndex#search}：BM25 算的就是这些词项。
 */
class TermsTest {

    @Test
    void chineseBecomesUnigramsAndBigrams() {
        assertThat(Terms.of("质量问题")).containsExactly("质", "质量", "量", "量问", "问", "问题", "题");
    }

    /** 停用字既不单独成词项，也不和前后的字组成双字：「坏了」「退吗」都不要。 */
    @Test
    void stopCharsAreDroppedWithTheirBigrams() {
        assertThat(Terms.of("东西坏了能退吗")).containsExactly("东", "东西", "西", "西坏", "坏", "能", "能退", "退");
        assertThat(Terms.of("积分多久过期")).contains("过期");
    }

    @Test
    void lettersAndDigitsStayTogetherAndLowercase() {
        assertThat(Terms.of("iPhone 满99元包邮")).containsExactly("iphone", "满", "99", "元", "元包", "包", "包邮", "邮");
    }

    @Test
    void punctuationSplitsBigrams() {
        assertThat(Terms.of("发票，开票")).containsExactly("发", "发票", "票", "开", "开票", "票");
        assertThat(Terms.of(null)).isEmpty();
        assertThat(Terms.of("的了吗？")).isEmpty();
    }
}
