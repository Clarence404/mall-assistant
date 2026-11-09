package io.github.clarence404.assistant.aftersales;

import java.time.Instant;

/**
 * 一张售后申请单：用户确认后由智能客服记下来，状态「待人工审核」（第 7 章引入）。
 *
 * <p>所在层：售后申请的领域模型。{@code agent.ConfirmationService} 在用户点确认后创建，存进 {@link AfterSalesStore}（内存或 PostgreSQL）；
 * {@code GET /api/agent/after-sales} 列出自己的申请单。
 *
 * <p><b>为什么记在智能客服这边、而且只「记」不「办」</b>：配套的商城 mall-evolution 没有用户侧的售后 / 退款接口，退款要走支付渠道、要核对签收和
 * 商品状况，本来就该由人来审。所以 Agent 能做的只是把「谁、哪一单、要退款还是退货、为什么」整理成一张申请单交给人工客服——<b>不动钱、不改订单</b>。
 * 真实系统里这一步应该调商城自己的售后接口（创建售后单，由商城的售后流程去审核、退款），本地这张表只是为了把 Agent 的流程走通。
 *
 * @param id 申请单号，如 AS20261109-3F2A9C1B
 * @param ownerKey 谁的：商城用户（{@code user:10001}），和第 6 章的会话归属同一个写法
 * @param orderNo 订单号
 * @param type 售后类型
 * @param reason 用户说的原因
 * @param status 状态，现在只有「待人工审核」
 * @param confirmationId 由哪一次确认生成：同一个确认只会生成一张申请单（重复点确认、并发点确认都不会多出一张）
 * @param createdAt 提交时间
 */
public record AfterSalesApplication(
        String id,
        String ownerKey,
        String orderNo,
        Type type,
        String reason,
        Status status,
        String confirmationId,
        Instant createdAt) {

    /** 售后类型：按订单状态由代码决定，不让模型填。 */
    public enum Type {
        /** 仅退款：已付款、还没发货。 */
        REFUND_ONLY("仅退款"),
        /** 退货退款：已发货或已收货，要先把东西寄回来。 */
        RETURN_REFUND("退货退款");

        private final String text;

        Type(String text) {
            this.text = text;
        }

        /** 中文名。 */
        public String text() {
            return text;
        }
    }

    /** 申请单状态。 */
    public enum Status {
        /** 待人工审核：客服 24 小时内处理（见知识库「售后与投诉 > 申请售后」）。 */
        PENDING_REVIEW("待人工审核");

        private final String text;

        Status(String text) {
            this.text = text;
        }

        /** 中文名。 */
        public String text() {
            return text;
        }
    }
}
