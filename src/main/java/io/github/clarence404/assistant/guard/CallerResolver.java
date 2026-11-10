package io.github.clarence404.assistant.guard;

import io.github.clarence404.assistant.memory.ConversationException;
import io.github.clarence404.assistant.memory.ConversationOwners;
import io.github.clarence404.assistant.ops.UsageProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * 一个对话请求「算谁的」：限流、每日预算、Token 记账都按它分（第 9 章引入）。
 *
 * <p>所在层：上线保护。上游是 {@code ChatController}；下游是第 6 章的 {@link ConversationOwners}（问商城「这个令牌是谁」，按令牌指纹缓存 5 分钟）。
 *
 * <ul>
 *   <li>登录了：{@code user:<商城用户 ID>}——换了令牌（重新登录）还是同一个人，额度不会因为重新登录就重置
 *   <li>没登录：{@code ip:<来源地址>}——匿名的只能按 IP 分；同一个公司出口 IP 后面的很多人会共用一份额度，这是按 IP 限流的老问题
 *   <li>带了令牌但商城确认不了（令牌过期、商城挂了）：{@code token:<令牌指纹前 12 位>}，这一轮照常往下走，真正的「请重新登录」由后面的会话、工具给出
 * </ul>
 *
 * <p>来源地址取 {@code request.getRemoteAddr()}：部署在 Nginx / 网关后面时它是代理的地址，要配 {@code server.forward-headers-strategy}
 * 让 Spring 按 {@code X-Forwarded-For} 还原，而且只能信任自己的代理加的头（否则谁都能伪造一个 IP 绕过限流）。上线清单里有这一条。
 *
 * <p>限流和预算都关掉时，不需要稳定的用户身份，就不多问一次商城，直接用令牌指纹（前几章的接口测试会断言商城收到了几次请求）。
 */
@Component
public class CallerResolver {

    private final ConversationOwners owners;
    private final GuardProperties guard;
    private final UsageProperties usage;

    /**
     * 创建。
     *
     * @param owners 第 6 章的归属（问商城，带缓存）
     * @param guard {@code assistant.guard.*}
     * @param usage {@code assistant.usage.*}
     */
    public CallerResolver(ConversationOwners owners, GuardProperties guard, UsageProperties usage) {
        this.owners = owners;
        this.guard = guard;
        this.usage = usage;
    }

    /**
     * 这个请求算谁的。
     *
     * @param mallToken 商城令牌，没登录为 null
     * @param remoteAddr 来源地址
     * @return {@code user:…}、{@code ip:…} 或 {@code token:…}
     */
    public String resolve(String mallToken, String remoteAddr) {
        if (mallToken == null) {
            return "ip:" + remoteAddr;
        }
        if (!guard.enabled() && usage.dailyTokenBudget() <= 0) {
            return "token:" + fingerprint(mallToken);
        }
        try {
            return owners.resolve(mallToken).key();
        } catch (ConversationException e) {
            return "token:" + fingerprint(mallToken);
        }
    }

    /** 令牌指纹：SHA-256 前 12 个十六进制字符，不留令牌原文。 */
    static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
