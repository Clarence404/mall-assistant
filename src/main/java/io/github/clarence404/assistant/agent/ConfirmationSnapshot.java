package io.github.clarence404.assistant.agent;

/**
 * 一个待确认操作此刻的样子：卡片内容 + 现在的状态（第 8 章引入）。
 *
 * <p>所在层：接口层的数据。{@link ConfirmationService#snapshot} 生成；两个地方用它：
 *
 * <ul>
 *   <li>{@code GET /api/agent/confirmations/{id}}：用户打开 MCP 工具给的确认链接（{@code /?confirm=<ID>}）时，页面拿它画确认卡片
 *   <li>MCP 工具 {@code getConfirmation}：对面的模型查「用户点了没有、结果是什么」
 * </ul>
 *
 * @param confirmation 卡片内容（说明、影响、过期时间），和第 7 章推给页面的是同一个对象
 * @param state {@link State}
 * @param message 结果说明：执行了 / 没执行的固定话术（和第 7 章确认接口返回的一字不差）；还没决定时是一句提示
 */
public record ConfirmationSnapshot(PendingConfirmation confirmation, State state, String message) {

    /** 待确认操作的状态。 */
    public enum State {
        /** 还没点，没过期：等用户确认。 */
        PENDING,
        /** 点了确认，执行成功。 */
        EXECUTED,
        /** 点了确认，但执行失败（商城拒绝、不可用等），什么都没改。 */
        FAILED,
        /** 点了取消，没有执行。 */
        REJECTED,
        /** 过期了也没点，没有执行。 */
        EXPIRED
    }
}
