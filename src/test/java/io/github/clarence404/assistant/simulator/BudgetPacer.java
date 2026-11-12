package io.github.clarence404.assistant.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按每日 Token 预算给提问「配速」：第 9 章每个调用方（登录用户、匿名按 IP）每天最多用 daily-token-budget 个 Token，超了 429
 * TOKEN_BUDGET_EXCEEDED、零点才恢复。模拟器要能连续跑一整天，就不能一上午把额度用光、下午晚上全是 429。
 *
 * <p>规则：到一天中的某个时刻，某个调用方最多用到「预算 ×（1 − 留给真人的比例）×（已过去的比例 + 1/24）」。也就是把可用额度按时间均匀摊开，
 * 再多给一小时的量当缓冲；用量以智能客服返回的 usage（今日已用、预算）为准，所以真人在页面上用掉的也会算进来。
 *
 * <p>预算调大（或设成 0 不限）后，配速自然就不再起作用，提问速度只由配置的间隔决定。
 *
 * <p>多个虚拟用户线程共用一个，状态放在并发容器里，每个调用方的记录整体替换。
 */
final class BudgetPacer {

    /** 一个调用方今天的情况。 */
    record Account(LocalDate day, long used, long budget, boolean exhausted) {}

    private final double reserve;
    private final ZoneId zone;
    private final Clock clock;
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    /**
     * 创建。
     *
     * @param reserve 留给真人的比例（0～1），比如 0.1 表示模拟器最多用九成
     * @param zone 「一天」按哪个时区算，和智能客服的 assistant.usage.zone 一致
     * @param clock 时钟（测试里传固定时钟）
     */
    BudgetPacer(double reserve, ZoneId zone, Clock clock) {
        this.reserve = Math.clamp(reserve, 0.0, 1.0);
        this.zone = zone;
        this.clock = clock;
    }

    /**
     * 用智能客服返回的数字更新（每轮回答的 usage、或 GET /api/usage/me）。
     *
     * @param caller 调用方（模拟器自己的键：anonymous 或手机号）
     * @param todayTokens 今日已用
     * @param budget 每日预算，0 表示不限
     */
    void update(String caller, long todayTokens, long budget) {
        accounts.put(caller, new Account(today(), todayTokens, budget, false));
    }

    /**
     * 没拿到 usage 时（比如流式中途断了）本地先记上估计的用量，宁可多算。
     *
     * @param caller 调用方
     * @param tokens 估计用了多少
     */
    void add(String caller, long tokens) {
        accounts.compute(caller, (k, a) -> {
            Account cur = current(a);
            return new Account(cur.day(), cur.used() + tokens, cur.budget(), cur.exhausted());
        });
    }

    /**
     * 收到 429 TOKEN_BUDGET_EXCEEDED：今天不再用这个调用方。
     *
     * @param caller 调用方
     */
    void exhausted(String caller) {
        accounts.compute(caller, (k, a) -> {
            Account cur = current(a);
            return new Account(cur.day(), cur.used(), cur.budget(), true);
        });
    }

    /**
     * 是否已经从服务端同步过（没同步过的调用方先查一次 GET /api/usage/me）。
     *
     * @param caller 调用方
     * @return 今天同步过返回 true
     */
    boolean known(String caller) {
        Account a = accounts.get(caller);
        return a != null && a.day().equals(today()) && a.budget() >= 0;
    }

    /**
     * 现在能不能让这个调用方再问一个大约要花 estimate 个 Token 的问题。
     *
     * @param caller 调用方
     * @param estimate 这一问的估计用量
     * @return 能问返回 true
     */
    boolean allow(String caller, long estimate) {
        Account a = current(accounts.get(caller));
        if (a.exhausted()) {
            return false;
        }
        if (a.budget() <= 0) {
            return true; // 不限，或还不知道预算（由调用方先同步）
        }
        return a.used() + estimate <= allowedSoFar(a.budget());
    }

    /**
     * 到现在为止最多可以用到多少。
     *
     * @param budget 每日预算
     * @return 预算 ×（1 − reserve）× min(1, 今天已过去的比例 + 1/24)
     */
    long allowedSoFar(long budget) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        double elapsed =
                Duration.between(now.toLocalDate().atStartOfDay(zone), now).toSeconds() / 86_400.0;
        return Math.round(budget * (1 - reserve) * Math.min(1.0, elapsed + 1.0 / 24));
    }

    /**
     * 今日合计（打印统计用）。
     *
     * @return 调用方 → 今天已用 / 预算
     */
    Map<String, Account> snapshot() {
        Map<String, Account> out = new TreeMap<>();
        accounts.forEach((k, v) -> out.put(k, current(v)));
        return out;
    }

    /** 过了零点：用量清零、解除「用完」标记，预算沿用。 */
    private Account current(Account a) {
        LocalDate today = today();
        if (a == null) {
            return new Account(today, 0, -1, false); // budget = -1 表示还没同步过
        }
        return a.day().equals(today) ? a : new Account(today, 0, a.budget(), false);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }
}
