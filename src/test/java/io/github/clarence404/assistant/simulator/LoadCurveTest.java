package io.github.clarence404.assistant.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoadCurveTest {

    /** 0 点 0.2、12 点 0.6、20 点 1.0，其余 0.4。 */
    private static List<Double> hourly() {
        List<Double> h = new ArrayList<>(Collections.nCopies(24, 0.4));
        h.set(0, 0.2);
        h.set(12, 0.6);
        h.set(20, 1.0);
        return h;
    }

    @Test
    void peakIsOneAndValuesAreNormalized() {
        List<Double> doubled = hourly().stream().map(v -> v * 2).toList(); // 整体放大不影响形状
        LoadCurve curve = new LoadCurve(doubled, 0);
        assertThat(curve.factor(LocalTime.of(20, 0))).isEqualTo(1.0);
        assertThat(curve.factor(LocalTime.of(12, 0))).isCloseTo(0.6, within(1e-9));
    }

    @Test
    void interpolatesWithinTheHourAndWrapsAroundMidnight() {
        LoadCurve curve = new LoadCurve(hourly(), 0);
        assertThat(curve.factor(LocalTime.of(11, 30))).isCloseTo(0.5, within(1e-9));
        assertThat(curve.factor(LocalTime.of(23, 30))).isCloseTo(0.3, within(1e-9)); // 23 点接回 0 点
    }

    @Test
    void neverBelowMinFactor() {
        LoadCurve curve = new LoadCurve(hourly(), 0.35);
        assertThat(curve.factor(LocalTime.of(0, 0))).isEqualTo(0.35);
        assertThat(curve.factor(LocalTime.of(20, 0))).isEqualTo(1.0);
    }

    @Test
    void activeShoppersFollowTheCurveButAtLeastOne() {
        LoadCurve curve = new LoadCurve(hourly(), 0);
        assertThat(curve.activeShoppers(LocalTime.of(20, 0), 12)).isEqualTo(12);
        assertThat(curve.activeShoppers(LocalTime.of(12, 0), 10)).isEqualTo(6);
        assertThat(curve.activeShoppers(LocalTime.of(0, 0), 2)).isEqualTo(1); // 0.4 四舍五入为 0，保底 1
    }

    @Test
    void rejectsBadConfig() {
        assertThatThrownBy(() -> new LoadCurve(List.of(1.0, 2.0), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoadCurve(Collections.nCopies(24, 0.0), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
