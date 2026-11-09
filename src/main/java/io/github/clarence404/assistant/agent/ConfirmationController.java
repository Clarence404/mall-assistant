package io.github.clarence404.assistant.agent;

import io.github.clarence404.assistant.mall.MallToken;
import io.github.clarence404.assistant.memory.ConversationIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 确认接口：{@code POST /api/agent/confirmations/{id}}，请求体 {@code {"decision": "approve" | "reject", "conversationId": "..."}}（第 7 章引入）。
 *
 * <p>所在层：接口层。上游是 {@code static/index.html} 的确认卡片（用户点「确认」/「取消」）；下游是 {@link ConfirmationService#decide}。
 * 这是写操作的<b>唯一</b>入口：对话接口（{@code /api/chat}）里不管模型输出什么、用户打什么字，都走不到执行那一步。
 *
 * <p>请求体故意只有两个字段：参数（订单号、新地址）在生成确认时就定死在服务端了，请求里多带的任何字段都被忽略（Jackson 默认不认识的字段直接丢掉），
 * 所以「篡改请求换一个订单号」没有用。归属靠 {@code Authorization} 请求头里的商城令牌，和对话接口同一套。
 */
@RestController
public class ConfirmationController {

    private final ConfirmationService confirmations;

    /**
     * 创建。
     *
     * @param confirmations 确认服务
     */
    public ConfirmationController(ConfirmationService confirmations) {
        this.confirmations = confirmations;
    }

    /**
     * 确认或取消一个待确认操作。
     *
     * @param id 确认 ID（UUID）
     * @param request 决定与会话 ID
     * @param authorization {@code Authorization: Bearer <商城令牌>}
     * @return 200 结果（执行成功 / 执行失败 / 已取消）；同样的决定重复提交返回原来的结果；不存在或不是你的 404；过期 410；决定冲突 409；登录失效 401
     */
    @PostMapping("/api/agent/confirmations/{id}")
    public ConfirmationOutcome decide(
            @PathVariable @Pattern(regexp = ConversationIds.PATTERN) String id,
            @Valid @RequestBody Request request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        ConfirmationOutcome.Decision decision =
                ConfirmationOutcome.Decision.valueOf(request.decision().toUpperCase(Locale.ROOT));
        return confirmations.decide(
                id, decision, request.conversationId(), MallToken.fromAuthorizationHeader(authorization));
    }

    /**
     * 确认请求的请求体。
     *
     * @param decision {@code approve}（确认）或 {@code reject}（取消），别的值 400
     * @param conversationId 页面当前的会话 ID（小写 UUID），单轮时不带；要和生成确认时的会话一致
     */
    public record Request(
            @NotNull @Pattern(regexp = "(?i)approve|reject") String decision,
            @Pattern(regexp = ConversationIds.PATTERN) String conversationId) {}
}
