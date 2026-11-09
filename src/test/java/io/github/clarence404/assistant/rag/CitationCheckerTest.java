package io.github.clarence404.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 第 5 章 TODO ch05-7：引用校验。回答里的 [n] 只能是 1～资料条数，对不上的去掉并记下来，一个都没引用时标出来。 */
class CitationCheckerTest {

    @Test
    void validCitationsAreKeptAndCollected() {
        CitationChecker.Check check = CitationChecker.check("签收后 7 天内可以退[1]，运费买家出[2][1]。", 3);

        assertThat(check.reply()).isEqualTo("签收后 7 天内可以退[1]，运费买家出[2][1]。");
        assertThat(check.cited()).containsExactly(1, 2);
        assertThat(check.invalid()).isEmpty();
        assertThat(check.uncited()).isFalse();
    }

    /** 资料只有 2 条，模型写了 [3]、[0]：去掉，记进 invalid。 */
    @Test
    void nonexistentNumbersAreRemoved() {
        CitationChecker.Check check = CitationChecker.check("可以退货[3]，运费买家出[2]，详见规则[0]。", 2);

        assertThat(check.reply()).isEqualTo("可以退货，运费买家出[2]，详见规则。");
        assertThat(check.cited()).containsExactly(2);
        assertThat(check.invalid()).containsExactly(3, 0);
    }

    /** 小模型常写的变体：[1, 2]、[1、3]、全角【2】，统一成 [n]，无效的编号单独去掉。 */
    @Test
    void variantsAreNormalized() {
        CitationChecker.Check check = CitationChecker.check("甲[1, 2]，乙[1、3]，丙【2】。", 2);

        assertThat(check.reply()).isEqualTo("甲[1][2]，乙[1]，丙[2]。");
        assertThat(check.cited()).containsExactly(1, 2);
        assertThat(check.invalid()).containsExactly(3);
    }

    @Test
    void replyWithoutCitationsIsFlagged() {
        CitationChecker.Check check = CitationChecker.check("七天内可以无理由退货。", 2);

        assertThat(check.reply()).isEqualTo("七天内可以无理由退货。");
        assertThat(check.cited()).isEmpty();
        assertThat(check.uncited()).isTrue();
    }

    /** 方括号里不是纯数字的不算引用，原样保留；只有无效编号时也算没引用。 */
    @Test
    void nonNumericBracketsAreLeftAlone() {
        CitationChecker.Check check = CitationChecker.check("[注意] 以商品页为准[5]。", 2);

        assertThat(check.reply()).isEqualTo("[注意] 以商品页为准。");
        assertThat(check.invalid()).containsExactly(5);
        assertThat(check.uncited()).isTrue();
    }
}
