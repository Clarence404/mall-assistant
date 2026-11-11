package io.github.clarence404.assistant.agent;

import java.time.Instant;

/**
 * 一个待确认操作在服务端的全部信息：做什么、归谁、哪个会话、什么时候过期、给页面看的卡片、从哪来、结果（第 10 章从
 * {@link ConfirmationService} 的内部类挪出来，因为它要存进 Redis）。
 *
 * <p>所在层：Agent 的领域模型。{@link ConfirmationService} 生成，{@link ConfirmationStore} 保存。归属与会话只记在这里，不靠页面传回来。
 * 要能变成 JSON 再变回来（Redis 里存的是 JSON 字符串），所以全是 record 和枚举，没有锁、没有 volatile 这种「只在一个进程里有意义」的东西——
 * 第 7 章每个确认一把 {@code ReentrantLock}，第 10 章换成了存储层的「抢占」（{@link ConfirmationStore#claim}）。
 *
 * @param id 确认 ID（随机 UUID）
 * @param action 写操作（参数在生成时定死）
 * @param ownerKey 归属（{@code user:10001}）
 * @param conversationId 会话 ID；MCP 来的、单轮的为 null
 * @param expiresAt 过期时间
 * @param view 推给页面的卡片
 * @param origin 从哪来（聊天里的 Agent / MCP 客户端）
 * @param outcome 结果；还没处理时为 null
 */
public record StoredConfirmation(
        String id,
        PendingAction action,
        String ownerKey,
        String conversationId,
        Instant expiresAt,
        PendingConfirmation view,
        Origin origin,
        ConfirmationOutcome outcome) {

    /**
     * 带上结果的副本。
     *
     * @param result 结果
     * @return 副本
     */
    public StoredConfirmation withOutcome(ConfirmationOutcome result) {
        return new StoredConfirmation(id, action, ownerKey, conversationId, expiresAt, view, origin, result);
    }

    /** 待确认操作从哪来（第 8 章）。 */
    public enum Origin {
        /** 聊天里的 Agent（第 7 章），绑定会话。 */
        CHAT,
        /** MCP 客户端调了 propose* 工具（第 8 章），没有会话。 */
        MCP
    }
}
