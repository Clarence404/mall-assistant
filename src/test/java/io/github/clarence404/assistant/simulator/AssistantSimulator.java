package io.github.clarence404.assistant.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * 智能客服的流量模拟器：长期运行，用几个虚拟用户像真人一样提问——匿名和登录混着来，规则类（走 RAG）、闲聊、查商品、查订单、偶尔让 Agent
 * 办事（只提议、默认不点确认），有时在同一个会话里追问，流式和阻塞接口各一半——让 Grafana 的「智能客服」面板一直有数据。
 *
 * <p>它是一个工具，不属于任何一章：放在 src/test 下，不进应用的 jar；不起 Spring 容器（同一个工程里起容器会把 Spring AI、Redis 等自动配置全带上），
 * 只用 JDK 的 HttpClient、Jackson 和 SnakeYAML。配置见 {@code src/test/resources/simulator/assistant-simulator.yml}。
 *
 * <p>三件事保证它能「一直跑」而不添乱：
 *
 * <ol>
 *   <li>速率低：默认两个虚拟用户，问完等 40～80 秒（流量最高时），夜里按曲线再拉长；Ollama 慢，同时问多了只会排队
 *   <li>按每日 Token 预算配速（{@link BudgetPacer}）：额度均匀摊到一整天，不会上午用光、下午全是 429
 *   <li>出错就退避（{@link Backoff}）：限流、太忙、模型熔断、连不上时等待时间翻倍，不狂刷
 * </ol>
 *
 * <p>启动：IDEA 运行配置「智能客服流量模拟（演示账号）」，或 {@code ./mvnw test-compile exec:java} 之类自行运行 main。停止：Ctrl+C 或 IDEA 的停止按钮。
 */
public final class AssistantSimulator {

    private static final Logger log = LoggerFactory.getLogger(AssistantSimulator.class);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String ANONYMOUS = "anonymous";
    private static final Set<String> LOGIN_CATEGORIES = Set.of("order", "agent");

    private final SimulatorConfig config;
    private final AssistantClient client;
    private final List<String> accounts;
    private final String password;
    private final LoadCurve curve;
    private final SimClock clock;
    private final BudgetPacer pacer;
    private final WeightedPicker<String> allCategories;
    private final WeightedPicker<String> anonymousCategories;
    private final Map<String, List<String>> questions = new LinkedHashMap<>();
    private final List<List<String>> followUps = new ArrayList<>();
    private final Map<String, Long> estimates = new HashMap<>();
    private final Stats stats = new Stats();
    private final Map<String, Login> logins = new ConcurrentHashMap<>();
    private final Map<String, Orders> orders = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    /** 一个账号的登录态（商城令牌），多个虚拟用户共用。 */
    private record Login(String token, Instant expiresAt) {}

    /** 一个账号最近的订单号（缓存 3 分钟）：普通的和待支付的。 */
    private record Orders(List<String> all, List<String> pending, Instant fetchedAt) {}

    AssistantSimulator(SimulatorConfig config) {
        this.config = config;
        this.client =
                new AssistantClient(config.string("simulator.base-url"), config.string("simulator.mall-base-url"));
        this.accounts = config.list("simulator.accounts");
        this.password = config.string("simulator.password");
        List<Double> hourly = config.list("simulator.curve.hourly").stream()
                .map(Double::parseDouble)
                .toList();
        this.curve = new LoadCurve(hourly, config.decimal("simulator.curve.min-factor"));
        this.clock = new SimClock(Clock.systemDefaultZone(), config.decimal("simulator.curve.acceleration"));
        this.pacer = new BudgetPacer(
                config.decimal("simulator.budget.reserve"),
                ZoneId.of(config.string("simulator.budget.zone")),
                Clock.systemDefaultZone());
        Map<String, Double> weights = new LinkedHashMap<>();
        config.children("simulator.categories").forEach((k, v) -> weights.put(k, Double.parseDouble(v)));
        this.allCategories = new WeightedPicker<>(weights);
        Map<String, Double> anonymous = new LinkedHashMap<>(weights);
        LOGIN_CATEGORIES.forEach(anonymous::remove);
        this.anonymousCategories = new WeightedPicker<>(anonymous);
        for (String category : weights.keySet()) {
            questions.put(category, config.list("questions." + category));
            estimates.put(category, Long.parseLong(config.string("simulator.token-estimates." + category)));
        }
        for (int i = 0; !config.list("questions.follow-ups[" + i + "]").isEmpty(); i++) {
            followUps.add(config.list("questions.follow-ups[" + i + "]"));
        }
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("simulator.accounts 至少要有一个演示账号");
        }
    }

    /**
     * 入口。
     *
     * @param args 不用；配置走 yml、环境变量、-D 系统属性
     */
    public static void main(String[] args) {
        AssistantSimulator sim = new AssistantSimulator(SimulatorConfig.load("simulator/assistant-simulator.yml"));
        sim.start();
        sim.awaitEnd();
    }

    // ———————————————————— 生命周期 ————————————————————

    void start() {
        running = true;
        printBanner();
        int askers = config.integer("simulator.askers");
        for (int i = 0; i < askers; i++) {
            threads.add(Thread.ofPlatform().name("asker-" + (i + 1)).start(this::askLoop));
        }
        threads.add(Thread.ofPlatform().name("stats").daemon(true).start(this::statsLoop));
        // Ctrl+C、IDEA 停止按钮：JVM 退出前跑这个钩子，打断虚拟用户（正在等的回答会被放弃），最多等 5 秒
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "shutdown"));
    }

    /** 等到停止：设了 duration 就到点停，否则一直等。 */
    void awaitEnd() {
        Duration duration = config.duration("simulator.duration");
        try {
            if (duration.isZero()) {
                for (Thread t : threads) {
                    t.join();
                }
            } else {
                Thread.sleep(duration);
                log.info("已运行 {}，按 simulator.duration 自动退出", duration);
                stop();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    synchronized void stop() {
        if (!running) {
            return;
        }
        log.info("正在停止……");
        running = false;
        threads.forEach(Thread::interrupt);
        for (Thread t : threads) {
            try {
                t.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info(
                "已停止。累计：提问 {}（成功 {}），Token {}，被预算配速挡下 {} 次",
                stats.total("提问"),
                stats.total("成功"),
                stats.total("Token"),
                stats.total("预算配速"));
    }

    private void printBanner() {
        LocalDateTime now = clock.now();
        int askers = config.integer("simulator.askers");
        Duration min = config.duration("simulator.interval.min");
        Duration max = config.duration("simulator.interval.max");
        double avgSeconds = (min.toSeconds() + max.toSeconds()) / 2.0 / Math.max(1, askers);
        log.info(
                "智能客服流量模拟器启动：目标 {}（查订单号经商城网关 {}），演示账号 {} 个 + 匿名",
                client.baseUrl(),
                config.string("simulator.mall-base-url"),
                accounts.size());
        log.info(
                "速率：{} 个虚拟用户，各自问完等 {}～{} 秒（高峰时合计约每 {} 秒一问，当前时段系数 {} → 约每 {} 秒一问），流式 {}%，登录 {}%，追问 {}%",
                askers,
                min.toSeconds(),
                max.toSeconds(),
                Math.round(avgSeconds),
                String.format("%.2f", curve.factor(now.toLocalTime())),
                Math.round(avgSeconds / curve.factor(now.toLocalTime())),
                Math.round(config.decimal("simulator.stream-ratio") * 100),
                Math.round(config.decimal("simulator.login-ratio") * 100),
                Math.round(config.decimal("simulator.follow-up-probability") * 100));
        log.info(
                "问题占比：{}；Agent 确认取消的概率 {}；每个调用方的每日预算留 {}% 给真人，按时间均匀配速",
                questions.keySet().stream()
                        .map(c -> c + " " + Math.round(allCategories.probability(c) * 100) + "%")
                        .collect(Collectors.joining("、")),
                config.decimal("simulator.agent.confirm-cancel-probability"),
                Math.round(config.decimal("simulator.budget.reserve") * 100));
    }

    private boolean pause(Duration d) {
        if (!running) {
            return false;
        }
        try {
            Thread.sleep(d.isNegative() ? Duration.ZERO : d);
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            log.warn(message);
        }
    }

    // ———————————————————— 一个虚拟用户 ————————————————————

    private void askLoop() {
        RandomGenerator random = ThreadLocalRandom.current();
        Backoff backoff = new Backoff(Duration.ofSeconds(30), Duration.ofMinutes(10));
        Duration min = config.duration("simulator.interval.min");
        Duration max = config.duration("simulator.interval.max");
        if (!pause(Duration.ofMillis(random.nextLong(1000, Math.max(2000, min.toMillis()))))) { // 错开启动
            return;
        }
        while (running) {
            Duration wait;
            boolean failed = visit(random);
            if (failed) {
                wait = backoff.onFailure(random);
                if (backoff.failures() == 3) {
                    log.warn(
                            "连续 3 次失败（连不上、限流、太忙或模型出错），退避 {} 秒；检查 {} 与 Ollama 是否正常", wait.toSeconds(), client.baseUrl());
                }
            } else {
                backoff.onSuccess();
                double factor = curve.factor(clock.now().toLocalTime());
                long base = random.nextLong(min.toMillis(), Math.max(min.toMillis() + 1, max.toMillis() + 1));
                wait = Duration.ofMillis((long) (base / factor)); // 夜里系数小，等得久
            }
            if (!pause(wait)) {
                return;
            }
        }
    }

    /**
     * 一次来访：可能是一个异常请求，也可能是一问（加一句追问）。
     *
     * @return 是否失败到需要退避
     */
    private boolean visit(RandomGenerator random) {
        double r = random.nextDouble();
        double empty = config.decimal("simulator.anomalies.empty-message");
        double tooLong = config.decimal("simulator.anomalies.too-long");
        double wrongPassword = config.decimal("simulator.anomalies.wrong-password");
        if (r < empty + tooLong) {
            stats.inc("异常请求");
            String message = r < empty ? "" : "啊".repeat(2001); // 校验失败直接 400，不花 Token
            return client.chat(message, null, null).status() < 0;
        }
        if (r < empty + tooLong + wrongPassword) {
            stats.inc("异常请求");
            String phone = accounts.get(random.nextInt(accounts.size()));
            return client.login(phone, "wrong-" + random.nextInt(1000)).status() < 0;
        }

        boolean wantLogin = random.nextDouble() < config.decimal("simulator.login-ratio");
        Caller first = chooseCaller(wantLogin, random);
        // 想登录的账号都没额度了，就匿名问（反之亦然）
        Caller caller = first != null ? first : chooseCaller(!wantLogin, random);
        if (caller == null) {
            stats.inc("预算配速");
            warnOnce(
                    "budget-" + LocalDateTime.now().toLocalDate() + "-"
                            + LocalDateTime.now().getHour(),
                    "所有调用方都到了配速线，暂停提问（每日预算 "
                            + pacer.snapshot().values().stream()
                                    .mapToLong(BudgetPacer.Account::budget)
                                    .max()
                                    .orElse(-1) + "）；想问得更快就调大智能客服的 assistant.usage.daily-token-budget，见 README");
            return false;
        }

        String conversationId = UUID.randomUUID().toString();
        List<String> turns;
        String category;
        if (random.nextDouble() < config.decimal("simulator.follow-up-probability")) {
            List<List<String>> usable = followUps.stream()
                    .filter(f -> caller.token() != null
                            || !questions.getOrDefault("order", List.of()).contains(f.getFirst()))
                    .toList();
            turns = usable.isEmpty() ? List.of() : usable.get(random.nextInt(usable.size()));
            category = "policy";
        } else {
            turns = List.of();
            category = null;
        }
        if (turns.isEmpty()) {
            category = (caller.token() == null ? anonymousCategories : allCategories).pick(random);
            String q = question(category, caller, random);
            turns = q == null ? List.of(questions.get("policy").getFirst()) : List.of(q);
            category = q == null ? "policy" : category;
        }
        long estimate = estimates.getOrDefault(category, 1500L) * turns.size();
        if (!pacer.allow(caller.key(), estimate)) {
            stats.inc("预算配速");
            return false;
        }
        for (int i = 0; i < turns.size(); i++) {
            if (i > 0 && !pause(Duration.ofMillis(random.nextLong(8000, 25000)))) { // 看完回答再追问
                return false;
            }
            AssistantClient.Answer answer = ask(turns.get(i), conversationId, caller, category, random);
            if (!answer.ok()) {
                return needsBackoff(answer);
            }
            maybeConfirm(answer, caller, conversationId, random);
        }
        return false;
    }

    /** 这一问算谁的：key 用于配速（anonymous 或手机号），token 为 null 表示匿名。 */
    private record Caller(String key, String token) {}

    /** 挑一个还有额度的调用方：登录时随机试各个账号，匿名只有一个。 */
    private Caller chooseCaller(boolean login, RandomGenerator random) {
        if (!login) {
            sync(ANONYMOUS, null);
            return pacer.allow(ANONYMOUS, 700) ? new Caller(ANONYMOUS, null) : null;
        }
        List<String> shuffled = new ArrayList<>(accounts);
        Collections.shuffle(shuffled, java.util.Random.from(random));
        for (String phone : shuffled) {
            String token = token(phone);
            if (token == null) {
                continue;
            }
            sync(phone, token);
            if (pacer.allow(phone, 700)) {
                return new Caller(phone, token);
            }
        }
        return null;
    }

    /** 第一次用某个调用方（或过了零点）时，问一次 GET /api/usage/me 拿到今日已用和预算。 */
    private void sync(String key, String token) {
        if (pacer.known(key)) {
            return;
        }
        AssistantClient.Response usage = client.usage(token);
        if (usage.ok() && usage.body() != null) {
            pacer.update(
                    key,
                    usage.body().path("todayTokens").asLong(0),
                    usage.body().path("dailyBudget").asLong(0));
        }
    }

    /** 某个账号的商城令牌：没有或快过期时经智能客服登录（POST /api/mall/login），多个虚拟用户共用。 */
    private String token(String phone) {
        Login login = logins.get(phone);
        if (login != null && Instant.now().isBefore(login.expiresAt().minusSeconds(60))) {
            return login.token();
        }
        AssistantClient.Response r = client.login(phone, password);
        if (r.ok() && r.body() != null && r.body().hasNonNull("accessToken")) {
            stats.inc("登录");
            long expiresIn = r.body().path("expiresIn").asLong(1800);
            logins.put(
                    phone,
                    new Login(
                            r.body().get("accessToken").asString(),
                            Instant.now().plusSeconds(expiresIn)));
            return logins.get(phone).token();
        }
        if (r.status() == 401) {
            warnOnce(
                    "login-" + phone, "演示账号 " + phone + " 登录失败（401）：mall-evolution 的演示数据里没有这个账号？检查 simulator.accounts");
        } else if (r.status() == 503 || r.status() < 0) {
            warnOnce("mall-down", "登录失败（HTTP " + r.status() + "）：mall-evolution 没启动？这段时间只匿名提问");
        }
        return null;
    }

    /** 按类别抽一个问题，把 {orderNo}、{pendingOrderNo} 换成这个账号真实的订单号；换不了的问题跳过，都换不了返回 null。 */
    private String question(String category, Caller caller, RandomGenerator random) {
        List<String> pool = new ArrayList<>(questions.getOrDefault(category, List.of()));
        Collections.shuffle(pool, java.util.Random.from(random));
        Orders mine = caller.token() == null ? null : orders(caller);
        for (String q : pool) {
            if (q.contains("{pendingOrderNo}")) {
                if (mine == null || mine.pending().isEmpty()) {
                    continue;
                }
                return q.replace(
                        "{pendingOrderNo}",
                        mine.pending().get(random.nextInt(mine.pending().size())));
            }
            if (q.contains("{orderNo}")) {
                if (mine == null || mine.all().isEmpty()) {
                    continue;
                }
                return q.replace(
                        "{orderNo}", mine.all().get(random.nextInt(mine.all().size())));
            }
            return q;
        }
        return null;
    }

    /** 这个账号最近 5 单（经商城网关查，缓存 3 分钟）；查不到返回空列表。 */
    private Orders orders(Caller caller) {
        Orders cached = orders.get(caller.key());
        if (cached != null && cached.fetchedAt().isAfter(Instant.now().minusSeconds(180))) {
            return cached;
        }
        AssistantClient.Response r = client.myOrders(caller.token());
        List<String> all = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        if (r.ok() && r.body() != null && r.body().path("items").isArray()) {
            for (JsonNode item : r.body().get("items")) {
                all.add(item.path("orderNo").asString());
                if ("PENDING_PAYMENT".equals(item.path("status").asString())) {
                    pending.add(item.path("orderNo").asString());
                }
            }
        }
        Orders fresh = new Orders(all, pending, Instant.now());
        orders.put(caller.key(), fresh);
        return fresh;
    }

    /** 问一句：按比例走流式或阻塞接口，记统计，用回答里的 usage 更新配速。 */
    private AssistantClient.Answer ask(
            String message, String conversationId, Caller caller, String category, RandomGenerator random) {
        // Agent 办事要走好几步（最长 120 秒），阻塞接口要干等，页面上这类请求一律用流式；其余按比例
        boolean stream = "agent".equals(category) || random.nextDouble() < config.decimal("simulator.stream-ratio");
        stats.inc("提问");
        stats.inc(stream ? "流式" : "阻塞");
        stats.inc(caller.token() == null ? "匿名" : "登录用户");
        long started = System.nanoTime();
        AssistantClient.Answer a = stream
                ? client.stream(message, conversationId, caller.token())
                : client.chat(message, conversationId, caller.token());
        long ms = (System.nanoTime() - started) / 1_000_000;
        if (a.ok()) {
            stats.inc("成功");
            stats.add("Token", a.turnTokens());
            stats.add("耗时毫秒", ms);
            if (a.todayTokens() >= 0) {
                pacer.update(caller.key(), a.todayTokens(), Math.max(0, a.dailyBudget()));
            } else {
                pacer.add(caller.key(), estimates.getOrDefault(category, 1500L));
            }
            log.debug(
                    "{} [{}] {} → {}（{} ms，{} Token）",
                    caller.key(),
                    stream ? "流式" : "阻塞",
                    message,
                    a.intent(),
                    ms,
                    a.turnTokens());
            return a;
        }
        stats.inc(a.errorCode());
        if ("TOKEN_BUDGET_EXCEEDED".equals(a.errorCode())) {
            pacer.exhausted(caller.key());
            warnOnce(
                    "exhausted-" + caller.key() + "-" + LocalDateTime.now().toLocalDate(),
                    "调用方 " + caller.key() + " 今日 Token 额度已用完（429），今天不再用它提问");
        } else {
            pacer.add(caller.key(), estimates.getOrDefault(category, 1500L) / 2); // 失败也可能花了一部分 Token
            log.info(
                    "{} 提问失败：{}（{}）",
                    caller.key(),
                    a.errorCode(),
                    message.length() > 20 ? message.substring(0, 20) + "…" : message);
        }
        return a;
    }

    /** 限流、太忙、模型出错、连不上要退避；预算用完、会话忙、参数错误等不用。 */
    private static boolean needsBackoff(AssistantClient.Answer a) {
        String code = a.errorCode() == null ? "" : a.errorCode();
        return a.status() < 0
                || a.status() >= 500
                || code.equals("RATE_LIMITED")
                || code.equals("TOO_BUSY")
                || code.equals("STATE_UNAVAILABLE")
                || code.startsWith("MODEL_")
                || code.equals("STREAM_INCOMPLETE");
    }

    /** Agent 提出「取消待支付订单」时，按配置的概率点确认（默认 0：只提议、不执行）。 */
    private void maybeConfirm(AssistantClient.Answer a, Caller caller, String conversationId, RandomGenerator random) {
        JsonNode c = a.confirmation();
        if (c == null || caller.token() == null) {
            return;
        }
        stats.inc("Agent 提议");
        String action = c.path("action").asString("").toLowerCase(Locale.ROOT);
        Orders mine = orders.get(caller.key());
        boolean pendingOrder =
                mine != null && mine.pending().contains(c.path("orderNo").asString(""));
        if (action.contains("cancel")
                && pendingOrder
                && random.nextDouble() < config.decimal("simulator.agent.confirm-cancel-probability")) {
            if (!pause(Duration.ofMillis(random.nextLong(3000, 10000)))) { // 看一眼卡片再点
                return;
            }
            AssistantClient.Response r =
                    client.decide(caller.token(), c.path("id").asString(), "confirm", conversationId);
            stats.inc(r.ok() ? "确认执行" : "确认失败");
        }
    }

    // ———————————————————— 统计 ————————————————————

    private void statsLoop() {
        Duration interval = config.duration("simulator.stats-interval");
        while (pause(interval)) {
            Map<String, Long> s = stats.drainInterval();
            long asked = s.getOrDefault("提问", 0L);
            long ok = s.getOrDefault("成功", 0L);
            Map<String, Long> errors = new LinkedHashMap<>(s);
            List.of("提问", "成功", "流式", "阻塞", "匿名", "登录用户", "Token", "耗时毫秒", "登录", "预算配速", "异常请求", "Agent 提议")
                    .forEach(errors::remove);
            Map<String, BudgetPacer.Account> budget = pacer.snapshot();
            long used = budget.values().stream()
                    .mapToLong(BudgetPacer.Account::used)
                    .sum();
            long cap = budget.values().stream()
                    .mapToLong(a -> Math.max(0, a.budget()))
                    .sum();
            LocalDateTime simNow = clock.now();
            log.info(
                    "最近 {} 秒：提问 {}（流式 {}、阻塞 {}；匿名 {}、登录 {}），成功 {}，平均 {} 秒，Token {}，失败 {}，异常请求 {}，Agent 提议 {}，被配速挡下 {}｜模拟时刻 {}（系数 {}）｜今日 Token 已用 {} / 预算合计 {}（{} 个调用方）｜累计提问 {}",
                    interval.toSeconds(),
                    asked,
                    s.getOrDefault("流式", 0L),
                    s.getOrDefault("阻塞", 0L),
                    s.getOrDefault("匿名", 0L),
                    s.getOrDefault("登录用户", 0L),
                    ok,
                    ok == 0 ? "-" : String.format("%.1f", s.getOrDefault("耗时毫秒", 0L) / 1000.0 / ok),
                    s.getOrDefault("Token", 0L),
                    errors.isEmpty() ? "0" : errors,
                    s.getOrDefault("异常请求", 0L),
                    s.getOrDefault("Agent 提议", 0L),
                    s.getOrDefault("预算配速", 0L),
                    HM.format(simNow),
                    String.format("%.2f", curve.factor(simNow.toLocalTime())),
                    used,
                    cap == 0 ? "不限" : cap,
                    budget.size(),
                    stats.total("提问"));
        }
    }
}
