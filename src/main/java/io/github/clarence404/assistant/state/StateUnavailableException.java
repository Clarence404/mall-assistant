package io.github.clarence404.assistant.state;

/**
 * 共享状态（Redis）暂时用不了，而这件事按策略不能「假装没事」（第 10 章引入）。
 *
 * <p>所在层：共享状态的异常。{@link StateFailures#allowOrThrow} 在 {@code on-redis-error=reject} 时抛出（限流、预算），
 * 待确认操作的 Redis 存储出错时也抛它（写操作永远不能「出错就放行」）。上游各自翻译成自己的错误：对话接口是 503 {@code STATE_UNAVAILABLE}
 * （{@code guard.ChatGuard}），确认接口是 503 {@code CONFIRMATION_UNAVAILABLE}（{@code agent.ConfirmationService}），MCP 是一句给对面模型看的原因。
 */
public class StateUnavailableException extends RuntimeException {

    private final String component;

    /**
     * 创建。
     *
     * @param component 哪一样状态，如 {@code rate:chat}、{@code usage}、{@code confirmation}
     * @param cause 底层的 Redis 异常
     */
    public StateUnavailableException(String component, Throwable cause) {
        super("共享状态暂时不可用：" + component, cause);
        this.component = component;
    }

    /** 哪一样状态。 */
    public String component() {
        return component;
    }
}
