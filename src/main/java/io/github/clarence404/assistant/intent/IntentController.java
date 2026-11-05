package io.github.clarence404.assistant.intent;

import io.github.clarence404.assistant.chat.ChatRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IntentController {

    private final IntentService intentService;

    public IntentController(IntentService intentService) {
        this.intentService = intentService;
    }

    /** 只做意图识别：{"message": "..."} → IntentResult；模型调不通时由 ApiExceptionHandler 转成 503。 */
    @PostMapping("/api/intent")
    public IntentResult classify(@Valid @RequestBody ChatRequest request) {
        return intentService.classify(request.message());
    }
}
