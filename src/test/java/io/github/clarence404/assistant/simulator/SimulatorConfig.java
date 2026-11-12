package io.github.clarence404.assistant.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.yaml.snakeyaml.Yaml;

/**
 * 模拟器的配置：先读 classpath 上的 {@code simulator/assistant-simulator.yml}，再用环境变量和 JVM 系统属性覆盖。
 *
 * <p>为什么不用 Spring Boot 的配置绑定：模拟器和智能客服在同一个 Maven 工程里，一旦起 Spring 容器，Spring AI、Redis、MCP 的自动配置和 application.yml
 * 都会跟着加载。这里只用 SnakeYAML 读一个文件，自己做「宽松绑定」的一小部分：
 *
 * <ul>
 *   <li>yml 里的层级拍平成 {@code a.b.c}，列表拍平成 {@code a[0]}、{@code a[1]}
 *   <li>系统属性 {@code -Dsimulator.base-url=...} 优先级最高
 *   <li>其次是环境变量：键转成大写、点和连字符换成下划线，如 {@code SIMULATOR_BASE_URL}；列表写成逗号分隔，如 {@code SIMULATOR_ACCOUNTS=13800000000,13800000001}
 * </ul>
 */
final class SimulatorConfig {

    private final Map<String, String> values;
    private final UnaryOperator<String> env;

    /**
     * 创建。
     *
     * @param values 拍平后的 yml
     * @param env 按环境变量名取值（测试里传假的），取不到返回 null
     */
    SimulatorConfig(Map<String, String> values, UnaryOperator<String> env) {
        this.values = values;
        this.env = env;
    }

    /**
     * 读 classpath 上的 yml，环境变量取真实的。
     *
     * @param resource classpath 路径
     * @return 配置
     */
    static SimulatorConfig load(String resource) {
        try (InputStream in = SimulatorConfig.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到配置文件 " + resource);
            }
            Object root = new Yaml().load(in);
            Map<String, String> flat = new LinkedHashMap<>();
            flatten("", root, flat);
            return new SimulatorConfig(flat, System::getenv);
        } catch (IOException e) {
            throw new IllegalStateException("读不了配置文件 " + resource, e);
        }
    }

    /** 把嵌套的 Map / List 拍平成 a.b[0].c 形式的键。 */
    static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> flatten(prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k, v, out));
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(prefix + "[" + i + "]", list.get(i), out);
            }
        } else if (node != null) {
            out.put(prefix, String.valueOf(node));
        }
    }

    /** 环境变量名：simulator.base-url → SIMULATOR_BASE_URL。 */
    static String envName(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    /**
     * 覆盖值（系统属性优先，其次环境变量）。
     *
     * @param key 完整键
     * @return 没有覆盖时返回 null
     */
    private String override(String key) {
        String sys = System.getProperty(key);
        return sys != null ? sys : env.apply(envName(key));
    }

    /**
     * 取字符串。
     *
     * @param key 完整键，如 simulator.base-url
     * @return 值
     * @throws IllegalArgumentException 没配
     */
    String string(String key) {
        String v = override(key);
        if (v == null) {
            v = values.get(key);
        }
        if (v == null) {
            throw new IllegalArgumentException("缺少配置 " + key);
        }
        return v.trim();
    }

    int integer(String key) {
        return Integer.parseInt(string(key));
    }

    double decimal(String key) {
        return Double.parseDouble(string(key));
    }

    boolean bool(String key) {
        return Boolean.parseBoolean(string(key));
    }

    Duration duration(String key) {
        return parseDuration(string(key));
    }

    /**
     * 取列表：有覆盖时按逗号拆开，否则读 key[0]、key[1]……
     *
     * @param key 完整键
     * @return 值列表
     */
    List<String> list(String key) {
        String v = override(key);
        if (v != null) {
            return List.of(v.split("\\s*,\\s*"));
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; values.containsKey(key + "[" + i + "]"); i++) {
            out.add(values.get(key + "[" + i + "]"));
        }
        return out;
    }

    /**
     * 取一组子键（如各类问题的权重）：yml 里有的子键都算上，每个子键都可单独覆盖。
     *
     * @param prefix 前缀，如 simulator.categories
     * @return 子键名 → 值，保持 yml 里的顺序
     */
    Map<String, String> children(String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : values.keySet()) {
            if (key.startsWith(prefix + ".")) {
                String child = key.substring(prefix.length() + 1);
                if (!child.contains(".") && !child.contains("[")) {
                    out.put(child, string(key));
                }
            }
        }
        return out;
    }

    /**
     * 解析时长：支持 500ms、40s、5m、2h、1d，也支持 ISO-8601（PT40S）；纯数字按秒。
     *
     * @param text 文本
     * @return 时长
     */
    static Duration parseDuration(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("p")) {
            return Duration.parse(text.trim().toUpperCase(Locale.ROOT));
        }
        if (t.endsWith("ms")) {
            return Duration.ofMillis(
                    Long.parseLong(t.substring(0, t.length() - 2).trim()));
        }
        char unit = t.charAt(t.length() - 1);
        if (Character.isDigit(unit)) {
            return Duration.ofSeconds(Long.parseLong(t));
        }
        long n = Long.parseLong(t.substring(0, t.length() - 1).trim());
        return switch (unit) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("看不懂的时长：" + text);
        };
    }
}
