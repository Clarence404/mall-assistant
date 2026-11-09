package io.github.clarence404.assistant.agent;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 售后 Agent 能用的工具（第 7 章引入）：5 个只读、3 个写操作，外加表示「办完了，回答用户」的 {@link #FINISH}。
 *
 * <p>所在层：Agent 的领域模型。模型每一步在 {@link AgentDecision#action()} 里填一个工具名，{@link AgentAction#parse} 用 {@link #from} 认出来；
 * {@link AgentLoop} 按 {@link #kind()} 决定怎么办：只读的直接执行（{@link AgentToolbox}），写操作<b>不执行</b>、只生成待确认操作
 * （{@link WriteActionGuard} → {@link ConfirmationService}）。
 *
 * <p><b>只读和写操作为什么要分开对待</b>：查订单、查规则查错了，最多答错一句话，用户再问一次就好；取消订单、改地址、提交售后是改动真实数据的动作，
 * 做错了收不回来（取消的订单不能恢复，地址改错了包裹就寄丢了）。模型会理解错、会被提示注入带偏、会把用户的「算了」理解成「确定」，所以写操作
 * 必须由用户本人在页面上点按钮确认，模型的任何输出都不能直接触发它。这也是 Agent 落地最基本的安全原则：<b>模型负责提议，人负责拍板，代码负责校验和执行</b>。
 */
public enum AgentTool {
    /** 列出我最近的订单（用户没给订单号时先用它）。 */
    LIST_MY_ORDERS("listMyOrders", Kind.READ, "列出我的订单", "无"),
    /** 查一笔订单：状态、商品、收货信息，以及代码算好的「这笔现在能办什么」。 */
    QUERY_ORDER("queryOrder", Kind.READ, "查订单", "orderNo"),
    /** 查一笔订单的支付状态。 */
    QUERY_PAYMENT("queryPayment", Kind.READ, "查支付单", "orderNo"),
    /** 查商品：名称、价格、是否在售、是否有货（复用第 3 章的工具）。 */
    QUERY_PRODUCT("queryProduct", Kind.READ, "查商品", "skuId"),
    /** 查售后规则：复用第 5 章的 RAG 检索，返回编了号的资料片段（不调模型）。 */
    SEARCH_POLICY("searchPolicy", Kind.READ, "查售后规则", "query"),
    /** 取消订单：只有待支付的能取消，调商城 cancel 接口。写操作，要用户确认。 */
    CANCEL_ORDER("cancelOrder", Kind.WRITE, "取消订单", "orderNo"),
    /** 改收货地址：待支付、待发货的能改，调商城 address 接口。写操作，要用户确认。 */
    CHANGE_ADDRESS("changeAddress", Kind.WRITE, "修改收货地址", "orderNo，receiver / phone / address 至少一项"),
    /** 提交售后（退款、退货）申请：在本地记一张「待人工审核」的申请单，不动钱。写操作，要用户确认。 */
    APPLY_AFTER_SALES("applyAfterSales", Kind.WRITE, "提交售后申请", "orderNo、reason"),
    /** 办完了或者办不了：给用户回答，循环结束。 */
    FINISH("finish", Kind.FINISH, "结束并回答", "answer");

    private final String toolName;
    private final Kind kind;
    private final String title;
    private final String parameters;

    AgentTool(String toolName, Kind kind, String title, String parameters) {
        this.toolName = toolName;
        this.kind = kind;
        this.title = title;
        this.parameters = parameters;
    }

    /** 给模型用的名字（提示词、结构化输出里都是它），如 queryOrder。 */
    public String toolName() {
        return toolName;
    }

    /** 只读、写操作还是结束。 */
    public Kind kind() {
        return kind;
    }

    /** 中文名，给人看（调试面板、确认卡片、给模型的报错说明）。 */
    public String title() {
        return title;
    }

    /** 需要的参数（只用于报错说明）。 */
    public String parameters() {
        return parameters;
    }

    /**
     * 宽松地认出模型填的工具名：忽略大小写、空格、下划线和连字符（{@code "query_order"}、{@code " QueryOrder "} 都认）。
     *
     * <p>和第 2 章 {@code Intent#from} 一个思路：模型的输出没有百分之百的保证，大小写写错这种「意思对了、格式差一点」的，宽松一点能少浪费一步；
     * 但认不出来的<b>绝不猜</b>（比如把 {@code "cancel"} 当成 cancelOrder），返回 null，由调用方把「没有这个工具」作为这一步的结果交还给模型。
     *
     * @param value 模型填的 action，可能为 null
     * @return 工具；认不出时为 null
     */
    public static AgentTool from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip().replaceAll("[\\s_-]", "").toLowerCase(Locale.ROOT);
        for (AgentTool tool : values()) {
            if (tool.toolName.toLowerCase(Locale.ROOT).equals(normalized)) {
                return tool;
            }
        }
        return null;
    }

    /** 全部工具名，用顿号连接（报错说明里列给模型看）。 */
    public static String names() {
        return Arrays.stream(values()).map(AgentTool::toolName).collect(Collectors.joining("、"));
    }

    /** 工具的种类。 */
    public enum Kind {
        /** 只读：直接执行，结果交还给模型。 */
        READ,
        /** 写操作：不执行，生成待确认操作，等用户点确认。 */
        WRITE,
        /** 结束：给用户回答。 */
        FINISH
    }
}
