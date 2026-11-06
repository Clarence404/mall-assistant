package io.github.clarence404.assistant.intent;

import io.github.clarence404.assistant.chat.ChatRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单独的意图识别接口 {@code POST /api/intent}，方便调试提示词、对比模型（不必走完整的对话流程）。
 *
 * <p>所在层：接口层（第 2 章引入）。上游是 HTTP 客户端；下游是 {@link IntentService}。请求体与对话接口共用 {@link ChatRequest}。
 */
@RestController
public class IntentController {

    private final IntentService intentService;

    public IntentController(IntentService intentService) {
        this.intentService = intentService;
    }

    /**
     * 只做意图识别：{"message": "..."} → IntentResult；模型调不通时由 ApiExceptionHandler 转成 503。
     *
     * @param request 请求体，校验规则同对话接口
     * @return 校验过的意图识别结果；模型输出解析不了时是 UNKNOWN 兜底结果，而不是报错
     * @throws io.github.clarence404.assistant.error.ModelCallException 模型连不上或超时
     */
    @PostMapping("/api/intent")
    public IntentResult classify(@Valid @RequestBody ChatRequest request) {
        return intentService.classify(request.message());
    }
}
