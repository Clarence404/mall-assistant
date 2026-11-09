package io.github.clarence404.assistant.agent;

import java.util.regex.Pattern;

/**
 * Agent 的固定话术：除了模型 finish 时给的回答，其余情况（等待确认、步数用完、反复重复、超时、登录失效）都由代码直接回，不经过模型（第 7 章引入）。
 *
 * <p>所在层：Agent 的话术。{@link AgentLoop}、{@link NativeAgentRunner}、{@link AgentService} 共用。
 *
 * <p>为什么「等待确认」的话由代码写、不让模型写：这句话决定了用户以为发生了什么。实测小模型常常在提出取消之后说「已为您取消订单」——其实什么都没执行。
 * 代码写的话术和卡片上的说明一字不差，用户不会被误导。
 */
public final class AgentReplies {

    /**
     * 模型自称「已为你办好」的说法：Agent 这一轮从来不执行写操作（执行只发生在用户点确认之后），说这种话一定是编的。
     * 只认「为你 / 帮你」+ 办理动作这种说法，「这笔订单已取消」这类陈述订单状态的话不算。
     */
    static final Pattern FALSE_CLAIM = Pattern.compile("(已|已经)(为|帮|给)(你|您)\\S{0,4}(取消|修改|改|提交|申请|退款|退货|办理|办好)");

    /** 模型自称办好了时，换成这句。 */
    static final String NOTHING_EXECUTED = "这一步还没有为你执行任何操作：取消订单、改地址、提交售后都要先生成确认卡片，由你点「确认」后才会办理。请再说一下要办的事。";

    private AgentReplies() {}

    /**
     * 提出写操作后的回答：和确认卡片上的说明一致，并说清楚「点了才执行」。
     *
     * @param pending 待确认操作
     * @return 回答
     */
    public static String awaitingConfirmation(PendingConfirmation pending) {
        long minutes = Math.max(1, pending.ttlSeconds() / 60);
        return "我准备" + pending.summary() + "。" + pending.impact() + "请在下面的确认卡片上点「确认」后才会办理（" + minutes
                + " 分钟内有效）；不需要的话点「取消」。";
    }

    /**
     * 步数用完。
     *
     * @param maxSteps 步数上限
     * @return 回答
     */
    public static String stepLimit(int maxSteps) {
        return "这件事我没能在 " + maxSteps + " 步内办完，没有执行任何操作。可以换个说法再说一次（比如带上订单号），或者联系人工客服。";
    }

    /** 反复做同一个调用。 */
    public static String repeated() {
        return "我在重复同样的查询，先停下来了，没有执行任何操作。可以换个说法再说一次，或者联系人工客服。";
    }

    /**
     * 超时。
     *
     * @param seconds 总时长上限，秒
     * @return 回答
     */
    public static String timeout(long seconds) {
        return "办理超时了（超过 " + seconds + " 秒），没有执行任何操作，请稍后再试。";
    }

    /** 页面关了。 */
    public static String cancelled() {
        return "已停止办理，没有执行任何操作。";
    }

    /** 商城登录失效。 */
    public static String loginExpired() {
        return "商城登录已失效，办不了这件事，请重新登录后再说一次。";
    }

    /**
     * 模型 finish 时的回答，自称已经办好了就换成 {@link #NOTHING_EXECUTED}。
     *
     * @param answer 模型的回答
     * @return 检查过的回答
     */
    public static String checkFinish(String answer) {
        return FALSE_CLAIM.matcher(answer).find() ? NOTHING_EXECUTED : answer;
    }
}
