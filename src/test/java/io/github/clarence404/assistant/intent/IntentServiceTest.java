package io.github.clarence404.assistant.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.chat.FakeChatModel;
import io.github.clarence404.assistant.error.ModelCallException;
import io.github.clarence404.assistant.error.ModelFailure;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.ResourceAccessException;

/**
 * 第 2 章：意图识别的结构化输出、重试与兜底，假模型，不联网。
 *
 * <p>验证：合法 JSON 转成 {@link IntentResult}；JSON Schema 作为模型参数交给厂商（原生结构化输出）、温度为 0；不支持原生的模型改为把格式说明拼进用户消息；
 * 输出包了 Markdown 代码块也能解析；解析不了重试一次（带更严格的提醒），仍不行兜底为 UNKNOWN；结果经过校验；模型连不上时不重试、直接抛出。
 * 为什么解析失败重试、连不上不重试：输出格式不对是模型「这次没写好」，再问一次常常就对了；连不上是服务的问题，马上再试多半还是连不上，
 * 交给上层返回 503（HTTP 层的重试由 Spring AI 的 retry 配置负责）。
 *
 * <p>手法：{@link FakeChatModel} 的 {@code willClassify(...)} 按调用顺序返回预设的原始文本，模拟模型第一次、第二次分别输出什么。
 */
class IntentServiceTest {

    private final FakeChatModel model = new FakeChatModel();
    private final IntentService service =
            new IntentService(ChatClient.builder(model).build(), new ClassPathResource("prompts/intent-v2.st"));

    @Test
    void parsesValidJsonIntoIntentResult() {
        model.willClassify(
                "{\"intent\":\"ORDER_STATUS\",\"confidence\":0.92,\"orderNo\":\"1849203385720832512\",\"skuId\":null}");

        IntentResult result = service.classify("我的订单 1849203385720832512 到哪了");

        assertThat(result).isEqualTo(new IntentResult(Intent.ORDER_STATUS, 0.92, "1849203385720832512", null));
        assertThat(model.prompts()).hasSize(1);
    }

    @Test
    void schemaGoesToProviderAndTemperatureIsZero() {
        service.classify("这款耳机还有货吗");

        Prompt prompt = model.lastPrompt();
        assertThat(prompt.getSystemMessage().getText()).contains("意图识别").contains("OUT_OF_SCOPE");
        assertThat(prompt.getUserMessage().getText()).contains("这款耳机还有货吗").doesNotContain("JSON Schema");
        assertThat(prompt.getOptions())
                .as("useProviderStructuredOutput()：Schema 作为模型参数传给厂商（Ollama 的 format）")
                .isInstanceOfSatisfying(
                        StructuredOutputChatOptions.class,
                        options -> assertThat(options.getOutputSchema())
                                .contains("ORDER_STATUS")
                                .contains("orderNo"));
        assertThat(prompt.getOptions().getTemperature()).isZero();
    }

    @Test
    void modelWithoutNativeSupportGetsFormatInstructionsInUserMessage() {
        FakeChatModel plain = new FakeChatModel(false);
        IntentService plainService =
                new IntentService(ChatClient.builder(plain).build(), new ClassPathResource("prompts/intent-v2.st"));
        plain.willClassify("{\"intent\":\"PRODUCT_INFO\",\"confidence\":0.8,\"orderNo\":null,\"skuId\":null}");

        assertThat(plainService.classify("这款耳机还有货吗").intent()).isEqualTo(Intent.PRODUCT_INFO);
        assertThat(plain.lastPrompt().getUserMessage().getText())
                .contains("这款耳机还有货吗")
                .contains("JSON Schema") // 退回到把格式说明拼进提示词
                .contains("ORDER_STATUS");
    }

    @Test
    void markdownFenceAroundJsonIsTolerated() {
        model.willClassify("""
                ```json
                {"intent":"AFTER_SALES","confidence":0.8,"orderNo":null,"skuId":null}
                ```""");

        assertThat(service.classify("杯子碎了怎么退款").intent()).isEqualTo(Intent.AFTER_SALES);
        assertThat(model.prompts()).hasSize(1);
    }

    @Test
    void garbageIsRetriedOnceWithStricterReminderThenFallsBackToUnknown() {
        model.willClassify("好的，这位用户想查订单。", "意图是 ORDER_STATUS");

        IntentResult result = service.classify("我的订单到哪了");

        assertThat(result).isEqualTo(IntentResult.unknown());
        assertThat(model.prompts()).hasSize(2);
        assertThat(model.prompts().get(0).getUserMessage().getText()).doesNotContain("只输出一个 JSON 对象");
        assertThat(model.prompts().get(1).getUserMessage().getText())
                .contains("我的订单到哪了")
                .contains("只输出一个 JSON 对象");
    }

    @Test
    void retrySucceedsOnSecondAttempt() {
        model.willClassify("我觉得是查订单", "{\"intent\":\"ORDER_STATUS\",\"confidence\":0.7}");

        IntentResult result = service.classify("我的订单到哪了");

        assertThat(result.intent()).isEqualTo(Intent.ORDER_STATUS);
        assertThat(result.confidence()).isEqualTo(0.7);
        assertThat(model.prompts()).hasSize(2);
    }

    /** 标签写法不规范、置信度给成 95、订单号只有 4 位：分别被纠正为 ORDER_STATUS、1.0、null。 */
    @Test
    void resultIsSanitized() {
        model.willClassify("{\"intent\":\"order-status\",\"confidence\":95,\"orderNo\":\"2118\",\"skuId\":\"20002\"}");

        IntentResult result = service.classify("订单 2118 到哪了");

        assertThat(result).isEqualTo(new IntentResult(Intent.ORDER_STATUS, 1.0, null, "20002"));
    }

    /** JSON 结构合法、只是标签不认识：不算解析失败，不重试，校验时归为 UNKNOWN。 */
    @Test
    void unknownLabelIsUnknownWithoutRetry() {
        model.willClassify("{\"intent\":\"查询物流\",\"confidence\":0.9,\"orderNo\":null,\"skuId\":null}");

        assertThat(service.classify("快递到哪了").intent()).isEqualTo(Intent.UNKNOWN);
        assertThat(model.prompts()).hasSize(1);
    }

    @Test
    void modelUnavailableIsNotSwallowed() {
        model.willFailClassification(
                new ResourceAccessException("I/O error", new ConnectException("Connection refused")));

        assertThatThrownBy(() -> service.classify("在吗"))
                .isInstanceOfSatisfying(
                        ModelCallException.class,
                        e -> assertThat(e.failure()).isEqualTo(ModelFailure.MODEL_UNAVAILABLE));
        assertThat(model.prompts()).hasSize(1); // 连不上不是格式问题，不重试
    }
}
