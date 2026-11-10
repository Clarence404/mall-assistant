package io.github.clarence404.assistant.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 评测集文件本身（第 9 章）：能读、编号不重复、每类都有、版本号由内容决定。start 时就是绿的。
 */
class EvalSetTest {

    @Test
    void repositoryEvalSetCoversEveryCategory() {
        EvalSet set = EvalFixtures.evalSet();

        assertThat(set.name()).isEqualTo("cases-v1.jsonl");
        assertThat(set.version()).hasSize(12);
        assertThat(set.cases()).hasSize(58);
        Map<EvalCase.Category, Long> counts =
                set.cases().stream().collect(Collectors.groupingBy(EvalCase::category, Collectors.counting()));
        assertThat(counts.keySet()).containsExactlyInAnyOrder(EvalCase.Category.values());
        assertThat(set.cases()).allSatisfy(c -> assertThat(c.expect().isEmpty()).isFalse());
        assertThat(set.cases())
                .filteredOn(c -> c.category() == EvalCase.Category.REWRITE)
                .allSatisfy(c -> assertThat(c.history()).isNotEmpty());
    }

    @Test
    void parsesLowercaseCategoriesAndDefaults() {
        EvalSet set = EvalSet.parse(
                "x.jsonl",
                "{\"id\":\"a\",\"category\":\"rag\",\"message\":\"积分多久过期\",\"expect\":{\"cited\":true}}\n\n");

        EvalCase c = set.cases().getFirst();
        assertThat(c.category()).isEqualTo(EvalCase.Category.RAG);
        assertThat(c.login()).isFalse();
        assertThat(c.history()).isEmpty();
        assertThat(c.expect().mustNotContain()).isEmpty();
    }

    @Test
    void versionChangesWithContentButNotWithLineEndings() {
        String line = "{\"id\":\"a\",\"category\":\"rag\",\"message\":\"q\",\"expect\":{\"cited\":true}}";

        String v1 = EvalSet.parse("x", line + "\n").version();
        assertThat(EvalSet.parse("x", line + "\r\n").version()).isEqualTo(v1);
        assertThat(EvalSet.parse("x", line.replace("\"q\"", "\"q2\"") + "\n").version())
                .isNotEqualTo(v1);
    }

    @Test
    void rejectsBadLines() {
        assertThatThrownBy(() -> EvalSet.parse("x", "不是 JSON"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第 1 行");
        assertThatThrownBy(
                        () -> EvalSet.parse("x", "{\"id\":\"a\",\"category\":\"rag\",\"message\":\"q\",\"expect\":{}}"))
                .hasMessageContaining("没有任何期望");
        String ok = "{\"id\":\"a\",\"category\":\"rag\",\"message\":\"q\",\"expect\":{\"cited\":true}}";
        assertThatThrownBy(() -> EvalSet.parse("x", ok + "\n" + ok))
                .hasMessageContaining("第 2 行")
                .hasMessageContaining("重复");
        assertThatThrownBy(() -> EvalSet.parse("x", "{\"id\":\"a\",\"message\":\"q\",\"expect\":{\"cited\":true}}"))
                .hasMessageContaining("缺 id / category / message");
    }

    @Test
    void categoriesMatchChapters() {
        assertThat(Arrays.stream(EvalCase.Category.values()).map(Enum::name))
                .containsExactly("INTENT", "TOOL", "RAG", "REWRITE", "AGENT", "INJECTION", "REFUSAL");
    }
}
