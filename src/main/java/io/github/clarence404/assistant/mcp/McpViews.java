package io.github.clarence404.assistant.mcp;

import java.time.Instant;
import java.util.List;

/**
 * MCP 工具返回给对面模型的数据：每个工具一个 record，转成 JSON 放进工具结果（第 8 章引入）。
 *
 * <p>所在层：MCP 的输出模型。由 {@link McpMallService} 生成，{@link McpResults#ok} 转 JSON。
 *
 * <p>和第 7 章 Agent 的「观察」（{@code agent.Observations}）很像，但是给<b>别人的模型</b>看的，有几处不同：不提 {@code finish} 这类只有我们循环里才有的动作；
 * 能办的操作写成 MCP 工具名（{@code proposeCancelOrder}）；每个结果都带一句 {@code note}，告诉对面「下一步该做什么、什么不要做」——
 * 第 7 章的经验，写在结果里的提示比写在说明里的更管用。手机号照样打码：对面是谁的模型、日志存在哪，我们都不知道，个人信息给得越少越好。
 */
public final class McpViews {

    private McpViews() {}

    /**
     * 一笔订单。
     *
     * @param found 是否查到
     * @param reason 没查到的原因
     * @param orderNo 订单号
     * @param status 中文状态
     * @param statusCode 商城的状态枚举名
     * @param payAmount 实付金额（元）
     * @param items 商品摘要
     * @param receiver 收货信息（手机号打码）
     * @param createdAt 下单时间
     * @param allowedProposals 这笔现在能提出的写操作（MCP 工具名），由代码按状态算好
     * @param note 下一步提示
     */
    public record Order(
            boolean found,
            String reason,
            String orderNo,
            String status,
            String statusCode,
            String payAmount,
            String items,
            String receiver,
            String createdAt,
            List<String> allowedProposals,
            String note) {

        static Order notFound(String orderNo, String reason) {
            return new Order(false, reason, orderNo, null, null, null, null, null, null, List.of(), null);
        }
    }

    /**
     * 我最近的订单。
     *
     * @param found 是否查成
     * @param reason 没查成的原因
     * @param orders 订单（最多 {@link McpMallService#LIST_SIZE} 笔）
     * @param note 下一步提示
     */
    public record OrderList(boolean found, String reason, List<Brief> orders, String note) {

        /**
         * 列表里的一笔。
         *
         * @param orderNo 订单号
         * @param status 中文状态
         * @param statusCode 状态枚举名
         * @param payAmount 实付金额
         * @param createdAt 下单时间
         * @param allowedProposals 能提出的写操作
         */
        public record Brief(
                String orderNo,
                String status,
                String statusCode,
                String payAmount,
                String createdAt,
                List<String> allowedProposals) {}
    }

    /**
     * 知识库检索结果。
     *
     * @param found 是否找到
     * @param reason 没找到的原因
     * @param snippets 资料片段（按相关度）
     * @param note 资料只是数据的说明
     */
    public record Knowledge(boolean found, String reason, List<Snippet> snippets, String note) {

        /**
         * 一段资料。
         *
         * @param index 编号，从 1 开始（对面回答时可以标 [1]）
         * @param title 文档标题
         * @param headingPath 标题路径，如「退换货 > 七天无理由退货」
         * @param source 来源文件，如 returns.md；同名的 MCP 资源是 {@code mall://knowledge/returns.md}
         * @param text 正文（截短）
         */
        public record Snippet(int index, String title, String headingPath, String source, String text) {}
    }

    /**
     * 写操作的提议结果：要么生成了一个待确认操作（带确认 ID 和链接），要么被拦下（带原因）。
     *
     * @param proposed 是否生成了待确认操作
     * @param executed 是否已经执行：MCP 工具<b>永远是 false</b>，执行只能由用户在页面上点确认
     * @param reason 被拦下的原因
     * @param confirmationId 确认 ID
     * @param confirmUrl 确认链接：用户打开它、登录同一个账号、点「确认」才会执行
     * @param summary 要做的事（代码按实际参数写的，和页面卡片上一字不差）
     * @param impact 执行后会怎样
     * @param expiresAt 过期时间
     * @param note 给对面模型的说明
     */
    public record Proposal(
            boolean proposed,
            boolean executed,
            String reason,
            String confirmationId,
            String confirmUrl,
            String summary,
            String impact,
            Instant expiresAt,
            String note) {

        static Proposal rejected(String reason) {
            return new Proposal(false, false, reason, null, null, null, null, null, null);
        }
    }

    /**
     * 一个待确认操作现在的状态（对面的模型用 getConfirmation 查「用户点了没有」）。
     *
     * @param confirmationId 确认 ID
     * @param state PENDING（等用户点）、EXECUTED、FAILED、REJECTED（用户点了取消）、EXPIRED（过期没点）
     * @param summary 要做的事
     * @param message 结果说明（执行了 / 没执行的固定话术）；还没决定时是提示
     * @param expiresAt 过期时间
     */
    public record ConfirmationState(
            String confirmationId, String state, String summary, String message, Instant expiresAt) {}
}
