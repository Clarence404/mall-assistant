package io.github.clarence404.assistant.memory;

import io.github.clarence404.assistant.mall.MallToken;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话接口（第 6 章引入）：{@code GET /api/conversations/{id}/messages} 查看会话的全部消息（页面刷新后恢复历史、调试用），
 * {@code DELETE /api/conversations/{id}} 清空会话。
 *
 * <p>所在层：接口层。上游是 {@code static/index.html}；下游是 {@link ConversationMemory}。和对话接口一样，商城令牌在
 * {@code Authorization: Bearer} 请求头里，用来确认会话归属：别人的会话和不存在的会话一样返回 404。
 *
 * <p>会话 ID 在路径里，格式不对（不是小写 UUID）直接 400：Spring MVC 看到参数上的 {@code @Pattern}，会自动做方法参数校验。
 */
@RestController
public class ConversationController {

    private final ConversationMemory memory;

    /**
     * 创建。
     *
     * @param memory 对话记忆
     */
    public ConversationController(ConversationMemory memory) {
        this.memory = memory;
    }

    /**
     * 查看会话。
     *
     * @param id 会话 ID（小写 UUID）
     * @param authorization {@code Authorization} 请求头，没登录时不带
     * @return 200 {@code {"conversationId": "...", "messages": [{"role": "user", "text": "...", "at": "..."}, ...]}}；不存在、已过期或不是你的
     *     404；登录失效 401；商城或记忆库不可用 503（均为 ProblemDetail，带 code）
     */
    @GetMapping("/api/conversations/{id}/messages")
    public ConversationHistory messages(
            @PathVariable @Pattern(regexp = ConversationIds.PATTERN) String id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return new ConversationHistory(id, memory.messages(id, MallToken.fromAuthorizationHeader(authorization)));
    }

    /**
     * 清空会话：删掉记忆里这个会话的全部消息，之后同一个 ID 就是新会话。
     *
     * @param id 会话 ID（小写 UUID）
     * @param authorization {@code Authorization} 请求头，没登录时不带
     * @return 204；不存在或不是你的 404；正在回答 409；登录失效 401；商城或记忆库不可用 503
     */
    @DeleteMapping("/api/conversations/{id}")
    public ResponseEntity<Void> clear(
            @PathVariable @Pattern(regexp = ConversationIds.PATTERN) String id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        memory.clear(id, MallToken.fromAuthorizationHeader(authorization));
        return ResponseEntity.noContent().build();
    }

    /**
     * 会话的全部消息。
     *
     * @param conversationId 会话 ID
     * @param messages 消息，按时间从早到晚
     */
    public record ConversationHistory(String conversationId, List<ConversationMessage> messages) {}
}
