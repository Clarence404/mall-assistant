package io.github.clarence404.assistant.state;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * Redis 键的命名，以及加载 Lua 脚本（第 10 章引入）。
 *
 * <p>所在层：共享状态的工具。所有 Redis 实现都用它拼键：{@code 前缀 + 用途 + 内容}，如 {@code mall-assistant:rate:chat:user:10001}。
 *
 * <p><b>为什么要前缀</b>：本项目和 mall-evolution 共用一台 Redis（开发机上的 mall-infra），还另选了一个 database 编号。前缀让
 * {@code SCAN 0 MATCH mall-assistant:*} 只列出我们的键，排查、清理都不会碰到别人的。
 *
 * <p><b>键里的花括号</b>：同一轮要一起改的几个键（如一天的 Token 账 {@code usage:{2026-11-11}:caller} 和 {@code usage:{2026-11-11}:intent}），
 * 把共同的部分用花括号括起来。单机 Redis 里花括号只是普通字符；到了 Redis Cluster，只有花括号里的部分参与分片（hash tag），
 * 这几个键就一定落在同一个节点上——Lua 脚本和事务要求它操作的键都在同一个节点，否则报 CROSSSLOT。
 *
 * @param prefix 前缀，如 {@code mall-assistant:}
 */
public record RedisKeys(String prefix) {

    /**
     * 拼一个键：前缀 + 各段用冒号连起来。
     *
     * @param parts 各段，如 {@code "rate", "chat", "user:10001"}
     * @return 完整的键
     */
    public String of(String... parts) {
        return prefix + String.join(":", parts);
    }

    /**
     * 从 classpath 的 {@code redis/} 目录加载一段 Lua 脚本。
     *
     * <p>Spring Data Redis 执行它时先用 {@code EVALSHA}（只发脚本的 SHA1，省带宽），Redis 说没见过（{@code NOSCRIPT}）再用 {@code EVAL} 把全文发过去。
     *
     * @param file 文件名，如 {@code sliding-window.lua}
     * @param resultType 返回值类型（Lua 的整数对应 {@link Long}）
     * @param <T> 返回值类型
     * @return 脚本
     */
    public static <T> DefaultRedisScript<T> script(String file, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/" + file)));
        script.setResultType(resultType);
        return script;
    }
}
