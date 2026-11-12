package io.github.clarence404.assistant.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimulatorConfigTest {

    private static SimulatorConfig withEnv(Map<String, String> env) {
        Map<String, String> flat = new LinkedHashMap<>();
        SimulatorConfig.flatten(
                "",
                Map.of(
                        "simulator",
                        Map.of(
                                "base-url", "http://localhost:8088",
                                "accounts", List.of("13800000000", "13800000001"),
                                "interval", Map.of("min", "40s"),
                                "categories", Map.of("policy", 40, "chitchat", 14))),
                flat);
        return new SimulatorConfig(flat, env::get);
    }

    @Test
    void readsFlattenedYaml() {
        SimulatorConfig c = withEnv(Map.of());
        assertThat(c.string("simulator.base-url")).isEqualTo("http://localhost:8088");
        assertThat(c.list("simulator.accounts")).containsExactly("13800000000", "13800000001");
        assertThat(c.duration("simulator.interval.min")).isEqualTo(Duration.ofSeconds(40));
        assertThat(c.children("simulator.categories"))
                .containsEntry("policy", "40")
                .containsEntry("chitchat", "14");
    }

    @Test
    void environmentVariablesOverride() {
        SimulatorConfig c = withEnv(Map.of(
                "SIMULATOR_BASE_URL", "http://127.0.0.1:8091",
                "SIMULATOR_ACCOUNTS", "13800000005, 13800000006",
                "SIMULATOR_CATEGORIES_POLICY", "0"));
        assertThat(c.string("simulator.base-url")).isEqualTo("http://127.0.0.1:8091");
        assertThat(c.list("simulator.accounts")).containsExactly("13800000005", "13800000006");
        assertThat(c.children("simulator.categories")).containsEntry("policy", "0");
    }

    @Test
    void missingKeyFailsWithItsName() {
        assertThatThrownBy(() -> withEnv(Map.of()).string("simulator.nope")).hasMessageContaining("simulator.nope");
    }

    @Test
    void parsesDurations() {
        assertThat(SimulatorConfig.parseDuration("500ms")).isEqualTo(Duration.ofMillis(500));
        assertThat(SimulatorConfig.parseDuration("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(SimulatorConfig.parseDuration("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(SimulatorConfig.parseDuration("30")).isEqualTo(Duration.ofSeconds(30));
        assertThat(SimulatorConfig.parseDuration("PT1M")).isEqualTo(Duration.ofMinutes(1));
        assertThat(SimulatorConfig.parseDuration("0s")).isZero();
    }

    @Test
    void envNameFollowsSpringStyle() {
        assertThat(SimulatorConfig.envName("simulator.follow-up-probability"))
                .isEqualTo("SIMULATOR_FOLLOW_UP_PROBABILITY");
    }

    @Test
    void bundledConfigBuildsAndIsGentle() {
        SimulatorConfig c = SimulatorConfig.load("simulator/assistant-simulator.yml");
        new AssistantSimulator(c); // 能建起来：问题、权重、曲线、配速都配齐了（不发请求）
        assertThat(c.integer("simulator.askers")).isLessThanOrEqualTo(2); // Ollama 慢，默认不多开
        assertThat(c.duration("simulator.interval.min")).isGreaterThanOrEqualTo(Duration.ofSeconds(20));
        assertThat(c.decimal("simulator.agent.confirm-cancel-probability")).isZero(); // 默认只提议、不执行
        assertThat(c.list("questions.policy")).hasSizeGreaterThan(10);
        assertThat(c.list("questions.follow-ups[0]")).hasSize(2);
        assertThat(c.children("simulator.categories"))
                .containsOnlyKeys(c.children("simulator.token-estimates").keySet());
    }
}
