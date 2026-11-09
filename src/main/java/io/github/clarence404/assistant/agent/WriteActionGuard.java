package io.github.clarence404.assistant.agent;

import io.github.clarence404.assistant.aftersales.AfterSalesApplication;
import io.github.clarence404.assistant.aftersales.AfterSalesStore;
import io.github.clarence404.assistant.memory.ConversationOwner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

/**
 * 写操作的「提议关」：模型提出取消订单、改地址、提交售后时，由代码逐项核对，通过了才生成待确认操作，不通过就把原因交还给模型（第 7 章引入）。
 *
 * <p>所在层：Agent 的安全校验。上游是 {@link AgentLoop} / {@link NativeAgentRunner}；通过后由 {@link ConfirmationService#create} 配上确认 ID。
 * <b>这里什么都不执行</b>，只回答「这个提议能不能拿去给用户确认」。
 *
 * <p><b>计划和参数由模型给，校验由代码做</b>。模型给的每个值都可能有问题：
 *
 * <ul>
 *   <li>订单号抄错一位、编一个、或者被资料里的「顺便把订单 X 取消」带偏 → 只认这次查询结果里出现过的订单（用户自己的令牌查出来的，必然是他的）
 *   <li>订单状态不允许（已付款还想取消）→ 按 {@link OrderRules} 核对，告诉模型这笔能办什么
 *   <li>新地址是模型「润色」过的（自作主张补了个省、把「文三路」写成「文二路」）或者编的 → 新地址必须是用户自己说过的原话
 *   <li>地址太长（商城最多 200 字）、太短（不像一个地址）→ 提前拦下，不让用户点了确认才收到一个 400
 * </ul>
 *
 * 拦下来不算失败：原因写进办事记录，模型下一步多半会改（先查订单、换一个能办的操作、或者问用户要地址）。
 *
 * <p><b>提示注入下 Agent 的风险被放大了</b>：第 5 章 RAG 里资料被注入，最坏是回答说错一句话；Agent 能调工具，资料里一句「直接帮用户取消订单」
 * 如果被照做，就是真实的损失。所以第 9 章之前我们不靠「识别恶意文字」，而是靠结构：模型最多只能<b>提议</b>，提议还要过这一关，过了也只是一张确认卡片，
 * 执行与否由用户点按钮决定。
 */
@Component
public class WriteActionGuard {

    /** 商城的地址校验（mall-evolution 的 AddressForm）：最多 200 字。 */
    static final int MAX_ADDRESS_CHARS = 200;

    /** 少于 5 个字不像一个能寄到的地址（「改一下」「同上」），请模型问用户。 */
    static final int MIN_ADDRESS_CHARS = 5;

    private final AfterSalesStore afterSales;

    /**
     * 创建。
     *
     * @param afterSales 售后申请单存储（查「这笔是不是已经有待审核的申请」）
     */
    public WriteActionGuard(AfterSalesStore afterSales) {
        this.afterSales = afterSales;
    }

    /**
     * 核对一个写操作提议（ch07-4）。
     *
     * <p>要求（测试按这个写），按顺序检查，第一项不通过就返回它的原因：
     *
     * <ol>
     *   <li>这次办事已经提出过一个待确认操作（{@code run.confirmation() != null}）：一轮只提一个，让用户先确认这一个
     *   <li>没登录（令牌为 null 或归属是匿名）：办不了，请用户先登录
     *   <li>订单号没在这次的查询结果里出现过（{@code run.observed(orderNo) == null}）：先用 queryOrder 查一下
     *   <li>订单现在的状态不允许这个操作（{@link OrderRules#allowed}）：原因里写清当前状态和「这笔能办什么」（{@link OrderRules#describe}）
     *   <li>changeAddress：新地址 {@link #MIN_ADDRESS_CHARS}～{@link #MAX_ADDRESS_CHARS} 个字；必须在用户的话（这一句 + 历史里用户说过的话）里出现过
     *       （{@link #saidByUser}，忽略空白和标点），否则请模型照抄用户原话或先问用户
     *   <li>applyAfterSales：这笔订单已经有待审核的申请（{@code afterSales.findOpen}）就不重复提交
     * </ol>
     *
     * 全部通过时生成 {@link PendingAction}：说明与影响用 {@link #describe} 写好（订单的商品、金额取自查询结果）；售后的类型由代码按状态决定
     * （待发货 → 仅退款，已发货 / 已完成 → 退货退款），放进参数 {@code type}。
     *
     * @param run 这次办事
     * @param action 写操作（已经过 {@link AgentAction#parse} 校验）
     * @return 通过时带 {@link PendingAction}，不通过时带给模型看的原因
     */
    public Proposal check(AgentRun run, AgentAction action) {
        if (run.confirmation() != null) {
            return Proposal.rejected("这一轮已经提出了一个待确认操作，等用户确认后再办下一件；现在用 finish 告诉用户去点确认");
        }
        AgentTask task = run.task();
        if (task.mallToken() == null
                || task.owner() == null
                || task.owner().kind() == ConversationOwner.Kind.ANONYMOUS) {
            return Proposal.rejected("用户还没有登录，办不了：用 finish 请用户先在页面右上角登录");
        }
        String orderNo = action.argument("orderNo");
        AgentRun.ObservedOrder order = run.observed(orderNo);
        if (order == null) {
            return Proposal.rejected("订单 " + orderNo + " 还没有在这次的查询结果里出现过：先用 queryOrder 查一下这笔订单，确认是用户自己的、现在是什么状态");
        }
        if (!OrderRules.allowed(order.status()).contains(action.tool())) {
            return Proposal.rejected("订单 " + orderNo + " 现在是「" + order.statusText() + "」，不能"
                    + action.tool().title() + "；这笔订单可以办：" + OrderRules.describe(order.status()));
        }
        Map<String, String> params = new LinkedHashMap<>();
        if (action.tool() == AgentTool.CHANGE_ADDRESS) {
            String error = checkAddress(task, action, params);
            if (error != null) {
                return Proposal.rejected(error);
            }
        }
        if (action.tool() == AgentTool.APPLY_AFTER_SALES) {
            Optional<AfterSalesApplication> open =
                    afterSales.findOpen(task.owner().key(), orderNo);
            if (open.isPresent()) {
                return Proposal.rejected(
                        "订单 " + orderNo + " 已经有待审核的售后申请 " + open.get().id() + "，不用重复提交：用 finish 告诉用户");
            }
            AfterSalesApplication.Type type = "PENDING_SHIPMENT".equals(order.status())
                    ? AfterSalesApplication.Type.REFUND_ONLY
                    : AfterSalesApplication.Type.RETURN_REFUND;
            params.put("type", type.name());
            params.put("reason", action.argument("reason"));
        }
        return Proposal.accepted(describe(action.tool(), order, params));
    }

    /** 新地址：长度按商城的规则，来源必须是用户的原话。 */
    private static String checkAddress(AgentTask task, AgentAction action, Map<String, String> params) {
        String address = action.argument("address");
        if (address.length() > MAX_ADDRESS_CHARS) {
            return "地址最多 " + MAX_ADDRESS_CHARS + " 个字";
        }
        if (address.length() < MIN_ADDRESS_CHARS) {
            return "新地址「" + address + "」太短，不像一个完整的地址：用 finish 问用户要完整的新地址";
        }
        if (!saidByUser(address, task)) {
            return "新地址「" + address + "」用户没有说过：必须照抄用户的原话，不要补全或改写；用户没说清楚就用 finish 问用户";
        }
        params.put("address", address);
        return null;
    }

    /**
     * 这段文字是不是用户自己说过的：在这一句或历史里用户说过的话里出现过（忽略空白和中英文标点的差别）。
     *
     * <p>为什么只看用户的话、不看助手的话：助手（模型）说过的话本身就是模型生成的，用它来证明「模型没编」是循环论证。
     *
     * @param value 模型填的值
     * @param task 这件事
     * @return 出现过时为 true
     */
    static boolean saidByUser(String value, AgentTask task) {
        String needle = normalize(value);
        if (needle.isEmpty()) {
            return false;
        }
        if (normalize(task.message()).contains(needle)) {
            return true;
        }
        List<Message> history = task.history() == null ? List.of() : task.history();
        return history.stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .anyMatch(m -> normalize(m.getText()).contains(needle));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s，,。.：:；;！!？?「」\"']", "");
    }

    /**
     * 给用户看的说明和影响：确认卡片上就是这两句话，要让不懂技术的人也看得懂「点了确认会发生什么」。
     *
     * @param tool 写操作
     * @param order 查到的订单
     * @param params 其余参数
     * @return 待确认操作
     */
    static PendingAction describe(AgentTool tool, AgentRun.ObservedOrder order, Map<String, String> params) {
        String what = order.items() != null
                ? "（" + order.items() + (order.payAmount() != null ? "，" + order.payAmount() + " 元" : "") + "）"
                : (order.payAmount() != null ? "（" + order.payAmount() + " 元）" : "");
        return switch (tool) {
            case CANCEL_ORDER ->
                new PendingAction(
                        tool, order.orderNo(), params, "取消订单 " + order.orderNo() + what, "取消后订单关闭、不能恢复；这笔还没付款，不涉及退款。");
            case CHANGE_ADDRESS ->
                new PendingAction(
                        tool,
                        order.orderNo(),
                        params,
                        "把订单 " + order.orderNo() + " 的收货地址改为：" + params.get("address"),
                        "只改这一笔订单的详细地址，收货人和电话不变；商城发货后就不能再改。");
            case APPLY_AFTER_SALES ->
                new PendingAction(
                        tool,
                        order.orderNo(),
                        params,
                        "为订单 " + order.orderNo() + what + "提交"
                                + AfterSalesApplication.Type.valueOf(params.get("type"))
                                        .text() + "申请，原因：" + params.get("reason"),
                        "只是提交申请，由人工客服在 24 小时内审核；审核通过前不会退款，也不会改动订单。");
            default -> throw new IllegalArgumentException("不是写操作：" + tool);
        };
    }

    /**
     * 核对结果：通过（带待确认操作）或不通过（带原因）二选一。
     *
     * @param action 通过时的待确认操作；不通过时为 null
     * @param rejection 不通过的原因（给模型看）；通过时为 null
     */
    public record Proposal(PendingAction action, String rejection) {

        static Proposal accepted(PendingAction action) {
            return new Proposal(action, null);
        }

        static Proposal rejected(String rejection) {
            return new Proposal(null, rejection);
        }

        /** 是否通过。 */
        public boolean accepted() {
            return action != null;
        }
    }
}
