package io.github.clarence404.assistant.mcp;

import io.github.clarence404.assistant.agent.AgentTool;
import io.github.clarence404.assistant.tool.MallTools;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 暴露给 MCP 客户端的工具：8 个 {@link McpTool @McpTool} 方法（第 8 章引入）。
 *
 * <p>所在层：MCP 的接口层（相当于 MCP 版的 Controller）。Spring AI 的 MCP Server Starter 启动时扫描带 {@code @McpTool} 的方法，生成工具清单
 * （名字、说明、参数的 JSON Schema），客户端 {@code tools/list} 拿到的就是它；{@code tools/call} 时按名字调到这里的方法。下游是 {@link McpGate}（进门）和
 * {@link McpMallService}（业务）。
 *
 * <p><b>MCP 是什么</b>：第 3 章的 {@code @Tool} 只能给<b>我们自己</b>的模型用；MCP（Model Context Protocol）把「工具清单 + 调用」做成一个标准协议
 * （JSON-RPC 2.0），任何支持 MCP 的客户端（Claude Desktop、Claude Code、Cursor、MCP Inspector……）连上 {@code http://localhost:8088/mcp} 就能列出这些工具、
 * 让它们自己的模型来调。能力写一次，到处能用。
 *
 * <p><b>暴露什么、不暴露什么</b>：
 *
 * <ul>
 *   <li>只读的直接执行：{@code searchKnowledge}、{@code queryProduct}（不用登录）、{@code listMyOrders}、{@code queryOrder}（要登录）
 *   <li>写操作只做「提议」：{@code proposeCancelOrder}、{@code proposeChangeAddress}、{@code proposeAfterSales} 走第 7 章的提议关，通过了也只登记一个待确认操作，
 *       返回确认 ID 和确认链接；真正执行只能由用户本人在智能客服页面上点「确认」。{@code getConfirmation} 查用户点了没有
 *   <li>不暴露：第 3 章的 {@code @Tool}（{@code spring.ai.mcp.server.tool-callback-converter=false}，只有显式标了 {@code @McpTool} 的方法才会出去），
 *       Agent 的循环本身（对面有自己的模型，不需要我们的模型替它想）
 * </ul>
 *
 * <p><b>说明是写给对面模型的提示词</b>：和第 3 章一样，{@code description} 决定了对面的模型什么时候调、参数怎么填。写操作的说明里讲清楚「只会生成待确认操作」，
 * 免得对面的模型对用户说「已为您取消」。{@link McpTool.McpAnnotations} 的几个提示（只读、会不会破坏数据、幂等）供客户端决定要不要先问用户再调——
 * 但它们只是<b>提示</b>，对面完全可以不理，所以真正的保证在服务端（提议关 + 人工确认）。
 *
 * <p>每个方法最后一个参数 {@link McpTransportContext} 由 Spring AI 注入（不会出现在参数 Schema 里），里面是 {@link McpAuth} 从 HTTP 头取出的令牌。
 * 方法自己把一切异常接住、翻成 {@code isError} 的结果：Spring AI 默认会把异常信息和根因原样拼进结果，可能把内部细节漏给对面。
 */
@Component
public class MallMcpTools {

    private static final Logger log = LoggerFactory.getLogger(MallMcpTools.class);

    static final String INTERNAL_ERROR = "工具执行出错了，没有改动任何数据，请稍后再试";

    private final McpGate gate;
    private final McpMallService service;
    private final MallTools mallTools;
    private final McpProperties properties;

    /**
     * 创建。
     *
     * @param gate 进门：身份、登录、次数
     * @param service 业务
     * @param mallTools 第 3 章的工具（查商品原样复用）
     * @param properties {@code assistant.mcp.*}（结果长度）
     */
    public MallMcpTools(McpGate gate, McpMallService service, MallTools mallTools, McpProperties properties) {
        this.gate = gate;
        this.service = service;
        this.mallTools = mallTools;
        this.properties = properties;
    }

    /**
     * 第 7 章的写操作对应的 MCP 工具名：cancelOrder → proposeCancelOrder。
     *
     * @param tool 写操作
     * @return MCP 工具名
     */
    public static String proposalTool(AgentTool tool) {
        return switch (tool) {
            case CANCEL_ORDER -> "proposeCancelOrder";
            case CHANGE_ADDRESS -> "proposeChangeAddress";
            case APPLY_AFTER_SALES -> "proposeAfterSales";
            default -> throw new IllegalArgumentException("不是写操作：" + tool);
        };
    }

    /**
     * 查知识库（不用登录）。
     *
     * @param query 要查的规则
     * @param context 传输上下文（Spring AI 注入）
     * @return 资料片段与来源
     */
    @McpTool(
            name = "searchKnowledge",
            title = "查商城规则",
            description = "在示例商城的规则文档（退换货、售后、配送、支付与发票、积分会员、账户安全）里检索，返回编了号的资料片段和来源文件。"
                    + "回答规则类问题前先用它，只依据返回的资料回答并标注 [编号]；没找到就如实说，不要凭常识编。不需要登录。",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public CallToolResult searchKnowledge(
            @McpToolParam(description = "要查的规则，一句话，如「七天无理由退货要什么条件」") String query, McpTransportContext context) {
        return call(context, "searchKnowledge", false, caller -> service.searchKnowledge(query));
    }

    /**
     * 查商品（不用登录）：原样复用第 3 章的工具。
     *
     * @param skuId 商品编号
     * @param context 传输上下文
     * @return 商品
     */
    @McpTool(
            name = "queryProduct",
            title = "查商品",
            description = "按商品编号（SKU）查示例商城的商品：名称、价格、是否在售、是否有货。不需要登录。",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public CallToolResult queryProduct(
            @McpToolParam(description = "商品编号 SKU：一串数字，如 20001") String skuId, McpTransportContext context) {
        return call(context, "queryProduct", false, caller -> mallTools.queryProduct(skuId));
    }

    /**
     * 列出我最近的订单（要登录）。
     *
     * @param context 传输上下文
     * @return 订单列表
     */
    @McpTool(
            name = "listMyOrders",
            title = "列出我的订单",
            description = "列出当前登录用户最近的 5 笔订单（订单号、状态、金额、下单时间、这笔现在能提出的操作）。用户没给订单号时先用它。需要登录（请求头带商城令牌）。",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public CallToolResult listMyOrders(McpTransportContext context) {
        return call(context, "listMyOrders", true, service::listMyOrders);
    }

    /**
     * 查一笔订单（要登录）。
     *
     * @param orderNo 订单号
     * @param context 传输上下文
     * @return 订单
     */
    @McpTool(
            name = "queryOrder",
            title = "查订单",
            description = "查当前登录用户自己的一笔订单：状态、商品、金额、收货信息（手机号打码）、下单时间，以及 allowedProposals（这笔现在能提出哪些操作）。"
                    + "别人的订单查不到。需要登录（请求头带商城令牌）。",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public CallToolResult queryOrder(
            @McpToolParam(description = "订单号：19 位或 23 位数字，照抄用户的话或 listMyOrders 的结果，不要编") String orderNo,
            McpTransportContext context) {
        return call(context, "queryOrder", true, caller -> service.queryOrder(caller, orderNo));
    }

    /**
     * 提出取消订单（要登录，只生成待确认操作）。
     *
     * @param orderNo 订单号
     * @param context 传输上下文
     * @return 确认 ID 与确认链接，或被拦下的原因
     */
    @McpTool(
            name = "proposeCancelOrder",
            title = "提出取消订单（需用户确认）",
            description =
                    "为一笔待支付的订单提出「取消订单」。不会立即执行：只生成一个待确认操作，返回确认 ID 和确认链接，" + "必须由用户本人在智能客服页面上点「确认」才会取消。不要对用户说「已取消」。需要登录。",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    public CallToolResult proposeCancelOrder(
            @McpToolParam(description = "订单号：19 位或 23 位数字") String orderNo, McpTransportContext context) {
        return call(
                context,
                "proposeCancelOrder",
                true,
                caller -> service.propose(caller, AgentTool.CANCEL_ORDER, orderNo, null));
    }

    /**
     * 提出修改收货地址（要登录，只生成待确认操作）。
     *
     * @param orderNo 订单号
     * @param address 新的详细地址
     * @param context 传输上下文
     * @return 确认 ID 与确认链接，或被拦下的原因
     */
    @McpTool(
            name = "proposeChangeAddress",
            title = "提出修改收货地址（需用户确认）",
            description = "为一笔待支付或待发货的订单提出「修改收货地址」（只改详细地址，收货人和电话不变）。不会立即执行：只生成待确认操作，"
                    + "必须由用户本人在智能客服页面上点「确认」才会修改。address 必须照抄用户说的新地址，不要补全或改写。需要登录。",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    public CallToolResult proposeChangeAddress(
            @McpToolParam(description = "订单号：19 位或 23 位数字") String orderNo,
            @McpToolParam(description = "新的详细地址，照抄用户的原话，5 到 200 个字") String address,
            McpTransportContext context) {
        return call(
                context,
                "proposeChangeAddress",
                true,
                caller -> service.propose(caller, AgentTool.CHANGE_ADDRESS, orderNo, address));
    }

    /**
     * 提出售后申请（要登录，只生成待确认操作）。
     *
     * @param orderNo 订单号
     * @param reason 售后原因
     * @param context 传输上下文
     * @return 确认 ID 与确认链接，或被拦下的原因
     */
    @McpTool(
            name = "proposeAfterSales",
            title = "提出售后申请（需用户确认）",
            description = "为一笔已付款的订单提出售后（退款 / 退货退款）申请，类型由系统按订单状态决定。不会立即提交：只生成待确认操作，"
                    + "必须由用户本人在智能客服页面上点「确认」才会提交；提交后也只是一张待人工审核的申请单，不会立即退款。需要登录。",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    public CallToolResult proposeAfterSales(
            @McpToolParam(description = "订单号：19 位或 23 位数字") String orderNo,
            @McpToolParam(description = "售后原因，照抄用户的话，如「收到的柚子坏了」") String reason,
            McpTransportContext context) {
        return call(
                context,
                "proposeAfterSales",
                true,
                caller -> service.propose(caller, AgentTool.APPLY_AFTER_SALES, orderNo, reason));
    }

    /**
     * 查一个提出过的操作的结果（要登录）。
     *
     * @param confirmationId 确认 ID
     * @param context 传输上下文
     * @return 状态：PENDING / EXECUTED / FAILED / REJECTED / EXPIRED
     */
    @McpTool(
            name = "getConfirmation",
            title = "查确认结果",
            description = "查一个 propose* 工具提出的操作现在怎样了：PENDING（等用户在页面确认）、EXECUTED（已执行）、FAILED（执行失败）、"
                    + "REJECTED（用户点了取消）、EXPIRED（过期没点）。需要登录，只能查自己提出的。",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public CallToolResult getConfirmation(
            @McpToolParam(description = "propose* 工具返回的 confirmationId") String confirmationId,
            McpTransportContext context) {
        return call(context, "getConfirmation", true, caller -> service.confirmation(caller, confirmationId));
    }

    /**
     * 所有工具的公共流程：进门 → 执行 → 包成结果（截断）；被拦下、出异常都翻成 {@code isError} 的固定话术。
     *
     * @param context 传输上下文
     * @param tool 工具名
     * @param loginRequired 是否要登录
     * @param body 业务
     * @return 工具结果
     */
    private CallToolResult call(
            McpTransportContext context, String tool, boolean loginRequired, Function<McpCaller, Object> body) {
        try {
            McpCaller caller = gate.enter(context, tool, loginRequired);
            return McpResults.ok(body.apply(caller), properties.maxResultChars());
        } catch (McpRejection e) {
            return McpResults.error(e.getMessage());
        } catch (RuntimeException e) {
            // 只记异常类型和消息：消息来自我们自己的代码和商城客户端，不含令牌（商城客户端打日志时也只打码后的令牌）
            log.warn("MCP 工具 {} 执行出错：{}", tool, e.toString());
            return McpResults.error(INTERNAL_ERROR);
        }
    }
}
