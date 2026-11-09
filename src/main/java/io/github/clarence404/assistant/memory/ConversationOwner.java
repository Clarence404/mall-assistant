package io.github.clarence404.assistant.memory;

/**
 * 会话归谁：匿名访客，或者某个商城用户（第 6 章引入）。
 *
 * <p>所在层：业务层的领域模型。{@link ConversationOwners#resolve} 根据请求里的商城令牌得出它，{@link ConversationOwners#memoryKey}
 * 用它和会话 ID 一起算出记忆库里的键。
 *
 * <p>匿名和登录<b>不混用</b>：同一个会话 ID，没登录时和登录后是两个不同的会话（键不同），登录前聊的内容登录后看不到，反之亦然。
 * 否则就会出现「先匿名问了几句、再登录，前面匿名说的话被当成这个用户说的」，或者「退出登录后，下一个用这台电脑的人看到上一个人的订单对话」。
 *
 * @param kind 匿名还是商城用户
 * @param userId 商城用户 ID；匿名时为 null
 */
public record ConversationOwner(Kind kind, String userId) {

    /** 匿名访客：没带商城令牌的请求。 */
    public static final ConversationOwner ANONYMOUS = new ConversationOwner(Kind.ANONYMOUS, null);

    /**
     * 商城用户。
     *
     * @param userId 商城确认过的用户 ID
     * @return 归属
     */
    public static ConversationOwner user(String userId) {
        return new ConversationOwner(Kind.USER, userId);
    }

    /**
     * 写进记忆键里的字符串：{@code anonymous} 或 {@code user:<用户 ID>}。
     *
     * @return 归属的字符串形式
     */
    public String key() {
        return kind == Kind.ANONYMOUS ? "anonymous" : "user:" + userId;
    }

    /** 归属的种类。 */
    public enum Kind {
        /** 没登录。 */
        ANONYMOUS,
        /** 登录了商城。 */
        USER
    }
}
