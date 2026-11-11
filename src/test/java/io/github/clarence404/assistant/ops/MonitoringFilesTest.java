package io.github.clarence404.assistant.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.assistant.guard.ChatGuard;
import io.github.clarence404.assistant.guard.GuardProperties;
import io.github.clarence404.assistant.guard.ModelCircuitBreaker;
import io.github.clarence404.assistant.state.StateConfig;
import io.github.clarence404.assistant.state.StateFailures;
import io.github.clarence404.assistant.state.StateProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 第 10 章：监控文件和代码对得上——面板和告警规则里写的每一个 {@code assistant_*} 指标名，应用真的会输出（指标名对不上，面板就是一片 No data，
 * 告警永远不响，而且没有任何报错）。
 *
 * <p>手法：不起 Spring，直接拿一个 Prometheus 注册表，把 {@link ChatMetrics} 的每个方法都调一遍、把熔断器 / 并发 / 共享状态的仪表都注册上，
 * 再 {@code scrape()} 出 Prometheus 文本——和 {@code /actuator/prometheus} 一样的名字（点换下划线、计数器加 {@code _total}、直方图加 {@code _bucket}）。
 * 然后从面板 JSON 和 {@code alerts.yml} 里用正则抠出所有 {@code assistant_xxx}，逐个检查。
 *
 * <p>另外检查面板能被 Grafana 的 provisioning 直接加载：数据源是模板变量 {@code ${datasource}}，不是导入时才替换的 {@code ${DS_PROMETHEUS}}
 * （第 9 章的写法只能手动 Import，provisioning 加载出来每个面板都报「找不到数据源」）。
 */
class MonitoringFilesTest {

    private static final Pattern METRIC = Pattern.compile("\\bassistant_[a-z_]+");
    private static String scrape;

    @BeforeAll
    static void scrapeEverything() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ChatMetrics metrics = new ChatMetrics(registry);
        TurnRecord turn = new TurnRecord("ip:127.0.0.1", null);
        turn.intent("POLICY");
        turn.rag("HIT", 0);
        turn.agent(3, "FINISHED");
        metrics.turn("chat", turn, ChatMetrics.OK);
        metrics.rejected("chat", "RATE_LIMITED");
        metrics.modelCall(new ModelCall("intent", "qwen2.5:1.5b", 500, 20, false, 800));
        metrics.modelCallFailed("answer");
        metrics.injectionBlocked("rag", "IGNORE_RULES");
        new ModelCircuitBreaker(
                GuardProperties.defaults(),
                ManualClock.standard(),
                new StaticListableBeanFactory(Map.of("registry", registry)).getBeanProvider(MeterRegistry.class));
        new ChatGuard(
                GuardProperties.defaults(),
                new UsageLedger(UsageProperties.of(0), ManualClock.standard()),
                metrics,
                ManualClock.standard());
        StateFailures failures = new StateConfig().stateFailures(StateProperties.defaults(), metrics);
        failures.record("rate:chat", new QueryTimeoutException("Redis 超时"));
        scrape = registry.scrape();
    }

    private static Set<String> metricsIn(Path file) throws IOException {
        Matcher m = METRIC.matcher(Files.readString(file, StandardCharsets.UTF_8));
        Set<String> names = new TreeSet<>();
        while (m.find()) {
            names.add(m.group());
        }
        return names;
    }

    /** 抓取结果里有没有这个名字的样本（行首是名字，后面是 { 或空格）。 */
    private static boolean exported(String name) {
        return Pattern.compile("(?m)^" + name + "[{ ]").matcher(scrape).find();
    }

    @Test
    void everyMetricInTheDashboardIsExported() throws IOException {
        Set<String> names = metricsIn(Path.of("monitoring/grafana/mall-assistant-dashboard.json"));

        assertThat(names).hasSizeGreaterThan(10);
        assertThat(names)
                .allSatisfy(n -> assertThat(exported(n)).as("面板里的 %s 应用没有输出", n).isTrue());
    }

    @Test
    void everyMetricInTheAlertRulesIsExported() throws IOException {
        Set<String> names = metricsIn(Path.of("monitoring/prometheus/alerts.yml"));

        assertThat(names).contains("assistant_state_errors_total", "assistant_model_circuit_state");
        assertThat(names)
                .allSatisfy(
                        n -> assertThat(exported(n)).as("告警规则里的 %s 应用没有输出", n).isTrue());
    }

    @Test
    void dashboardLoadsThroughProvisioning() throws IOException {
        String raw =
                Files.readString(Path.of("monitoring/grafana/mall-assistant-dashboard.json"), StandardCharsets.UTF_8);
        JsonNode dashboard = JsonMapper.builder().build().readTree(raw);

        assertThat(raw).doesNotContain("DS_PROMETHEUS");
        assertThat(dashboard.has("__inputs")).isFalse();
        assertThat(dashboard.path("templating").path("list").get(0).path("type").asString())
                .isEqualTo("datasource");
        for (JsonNode panel : dashboard.path("panels")) {
            assertThat(panel.path("datasource").path("uid").asString())
                    .as("面板 %s 的数据源", panel.path("title").asString())
                    .isEqualTo("${datasource}");
        }
    }

    @Test
    void provisionedDatasourceHasTheUidTheVariableSelects() throws IOException {
        String datasource = Files.readString(
                Path.of("monitoring/grafana/provisioning/datasources/prometheus.yml"), StandardCharsets.UTF_8);
        String compose = Files.readString(Path.of("monitoring/docker-compose.yml"), StandardCharsets.UTF_8);

        assertThat(datasource).contains("uid: prometheus", "type: prometheus", "url: http://prometheus:9090");
        assertThat(compose)
                .contains("prom/prometheus:v3.5.0", "grafana/grafana:11.6.1")
                .contains("${GRAFANA_ADMIN_PASSWORD:?")
                .as("口令只从 .env 读，不写在 compose 里")
                .doesNotContainPattern("GF_SECURITY_ADMIN_PASSWORD: [^$]");
    }
}
