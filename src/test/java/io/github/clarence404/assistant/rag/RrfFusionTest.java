package io.github.clarence404.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 第 5 章 TODO ch05-2：RRF 融合。分数都可以手算：第 r 名得 1 / (k + r)。 */
class RrfFusionTest {

    @Test
    void scoreIsSumOfReciprocalRanks() {
        List<RrfFusion.Fused> fused = RrfFusion.fuse(List.of(List.of("a", "b", "c"), List.of("c", "a")), 60);

        assertThat(fused).extracting(RrfFusion.Fused::key).containsExactly("a", "c", "b");
        assertThat(fused.get(0).score()).isCloseTo(1.0 / 61 + 1.0 / 62, within(1e-12));
        assertThat(fused.get(1).score()).isCloseTo(1.0 / 63 + 1.0 / 61, within(1e-12));
        assertThat(fused.get(2).score()).isCloseTo(1.0 / 62, within(1e-12));
    }

    /** 两边都排得靠前的块，胜过只在一边排第一的块：这就是混合检索想要的效果。 */
    @Test
    void agreementBeatsSingleFirstPlace() {
        List<RrfFusion.Fused> fused =
                RrfFusion.fuse(List.of(List.of("only-vector", "both"), List.of("only-keyword", "both")), 60);

        assertThat(fused.getFirst().key()).isEqualTo("both");
    }

    @Test
    void tiesKeepFirstAppearanceOrder() {
        List<RrfFusion.Fused> fused = RrfFusion.fuse(List.of(List.of("x", "y"), List.of("y", "x")), 60);

        assertThat(fused).extracting(RrfFusion.Fused::key).containsExactly("x", "y");
        assertThat(fused.get(0).score()).isEqualTo(fused.get(1).score());
    }

    @Test
    void duplicatesInOneRankingCountOnceAtBestRank() {
        List<RrfFusion.Fused> fused = RrfFusion.fuse(List.of(List.of("a", "a", "b")), 60);

        assertThat(fused).extracting(RrfFusion.Fused::key).containsExactly("a", "b");
        assertThat(fused.get(0).score()).isCloseTo(1.0 / 61, within(1e-12));
        assertThat(fused.get(1).score()).isCloseTo(1.0 / 62, within(1e-12));
    }

    /** k 越小越偏向各自的第一名：k = 1 时第 1 名得 1/2，第 2 名只得 1/3。 */
    @Test
    void smallerKWidensGapBetweenRanks() {
        List<RrfFusion.Fused> fused = RrfFusion.fuse(List.of(List.of("a", "b")), 1);

        assertThat(fused.get(0).score()).isCloseTo(0.5, within(1e-12));
        assertThat(fused.get(1).score()).isCloseTo(1.0 / 3, within(1e-12));
    }

    @Test
    void emptyRankingsGiveEmptyResult() {
        assertThat(RrfFusion.fuse(List.of(List.of(), List.of()), 60)).isEmpty();
    }
}
