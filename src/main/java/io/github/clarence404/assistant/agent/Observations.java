package io.github.clarence404.assistant.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 只读工具的结果长什么样（第 7 章引入）：每种结果一个 record，转成 JSON 写进办事记录交还给模型。
 *
 * <p>所在层：Agent 的数据。{@link AgentToolbox} 生成。和第 3 章的 {@code OrderLookup} 同一个思路：查不到也返回结构化结果（{@code found=false} +
 * 能直接说给用户听的 reason），不抛异常；{@code null} 字段不输出，结果越短越好。
 *
 * <p>和第 3 章不同的两处：
 *
 * <ul>
 *   <li>多了 {@code statusCode} 与 {@code allowedActions}：代码按商城规则算好「这笔现在能办什么」（{@link OrderRules}），模型照着选，不用自己猜
 *   <li>收货人手机号打了码：模型办事用不到完整号码，<b>数据最小化</b>——发给模型的个人信息越少，被记录、被套出来的风险越小
 * </ul>
 */
public final class Observations {

    private Observations() {}

    /**
     * 查一笔订单的结果。
     *
     * @param found 是否查到
     * @param reason 没查到的原因；查到时为 null
     * @param orderNo 订单号
     * @param status 中文状态，如「待支付（还没付款，超时未付会自动取消）」
     * @param statusCode 商城的状态枚举名，如 PENDING_PAYMENT
     * @param payAmount 实付金额
     * @param items 商品摘要
     * @param receiver 收货信息（手机号打码）
     * @param createdAt 下单时间
     * @param allowedActions 这笔订单现在能办的写操作（工具名），代码算好的
     * @param note 给模型的下一步提示（代码按 allowedActions 写的），同第 3 章 {@code OrderListLookup#note}：只在真的查到订单时出现，系统提示词保持短
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
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
            List<String> allowedActions,
            String note) {

        /** 没查到。 */
        static Order notFound(String orderNo, String reason) {
            return new Order(false, reason, orderNo, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * 列出我的订单的结果。
     *
     * @param found 是否查成（空列表也算查成）
     * @param reason 没查成的原因
     * @param orders 最近的订单，从新到旧
     * @param note 给模型的下一步提示
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderList(boolean found, String reason, List<Brief> orders, String note) {

        /** 列表里的一笔：比查一笔少了商品和收货信息（要看就再 queryOrder）。 */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Brief(
                String orderNo,
                String status,
                String statusCode,
                String payAmount,
                String createdAt,
                List<String> allowedActions) {}
    }

    /**
     * 查支付单的结果。
     *
     * @param found 是否查到
     * @param reason 没查到的原因
     * @param orderNo 订单号
     * @param status 中文支付状态，如「已支付」
     * @param paidAt 支付时间
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Payment(boolean found, String reason, String orderNo, String status, String paidAt) {}

    /**
     * 查售后规则的结果：第 5 章检索到的资料片段（编了号），不调模型。
     *
     * @param found 是否找到相关规定
     * @param reason 没找到的原因
     * @param sources 资料片段
     * @param note 提醒：资料只是数据
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Policy(boolean found, String reason, List<Snippet> sources, String note) {

        /**
         * 一段资料。
         *
         * @param index 编号
         * @param title 标题路径，如「退换货规则 > 质量问题退换货 > 申请时效」
         * @param content 正文（截短）
         */
        public record Snippet(int index, String title, String content) {}
    }
}
