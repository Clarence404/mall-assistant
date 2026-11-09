package io.github.clarence404.assistant.agent;

import java.time.Instant;
import java.util.Map;

/**
 * 推给页面的「待确认操作」：页面据此画一张确认卡片（第 7 章引入）。
 *
 * <p>所在层：接口层的数据。{@link ConfirmationService#create} 生成；阻塞接口放在响应的 {@code confirmation} 字段，流式接口作为 {@code confirm} 事件推出；
 * 用户点「确认」或「取消」时，页面拿 {@link #id} 调 {@code POST /api/agent/confirmations/{id}}。
 *
 * <p>里面<b>没有</b>归属和令牌：属于谁、属于哪个会话只记在服务端（{@link ConfirmationService}），不靠页面传回来的东西判断。
 *
 * @param id 确认 ID：随机 UUID，猜不出来；只有生成它的那个用户、在那个会话里才能确认
 * @param action 写操作的工具名：cancelOrder、changeAddress、applyAfterSales
 * @param title 中文名，如「取消订单」
 * @param orderNo 订单号
 * @param params 其余参数（改地址的新收货信息、售后原因与类型）
 * @param summary 给用户看的一句话说明
 * @param impact 执行后会怎样
 * @param expiresAt 过期时间：之后再点确认返回 410，要重新说一次
 * @param ttlSeconds 有效期，秒（页面显示「5 分钟内有效」）
 */
public record PendingConfirmation(
        String id,
        String action,
        String title,
        String orderNo,
        Map<String, String> params,
        String summary,
        String impact,
        Instant expiresAt,
        long ttlSeconds) {}
