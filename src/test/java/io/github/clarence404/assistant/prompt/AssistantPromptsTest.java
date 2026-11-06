package io.github.clarence404.assistant.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.chat.FakeChatModel;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 第 2 章：系统提示词模板——按版本选文件、渲染变量，以及拒答话术。
 *
 * <p>验证：{@code assistant.prompt.version} 决定用 {@code prompts/system-<版本>.st}，版本不存在时启动即失败（而不是运行时才发现）；
 * 模板变量（商城名、客服名、服务范围、今天的日期）都有值且渲染后不留 {@code {占位符}}；v2 比 v1 多了更严格的规则与示例；
 * 拒答话术带上商城名与服务范围。
 *
 * <p>手法：用 {@link TestPrompts} 固定时钟，「今天」永远是 2026-11-05，断言才稳定；渲染结果通过 ChatClient 发给 {@link FakeChatModel}，
 * 从它记下的 Prompt 里取系统消息，验证的是真正发给模型的文本。
 */
class AssistantPromptsTest {

    @Test
    void versionPropertySelectsTemplateFile() {
        assertThat(TestPrompts.prompts("v2").systemTemplate().getFilename()).isEqualTo("system-v2.st");
        assertThat(TestPrompts.prompts("v1").systemTemplate().getFilename()).isEqualTo("system-v1.st");
        assertThat(TestPrompts.prompts("v3").systemTemplate().getFilename()).isEqualTo("system-v3.st");
    }

    @Test
    void unknownVersionFailsFastAtStartup() {
        assertThatThrownBy(() -> TestPrompts.prompts("v9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system-v9.st");
    }

    @Test
    void variablesCarryShopScopeAndTodayInShopTimeZone() {
        Map<String, Object> variables = TestPrompts.prompts("v2").variables();

        assertThat(variables)
                .containsEntry("shopName", "示例商城")
                .containsEntry("assistantName", "小示")
                .containsEntry("supportScope", TestPrompts.SUPPORT_SCOPE)
                .containsEntry("today", "2026年11月5日 星期四");
    }

    @Test
    void renderedSystemMessageHasNoPlaceholdersLeft() {
        FakeChatModel model = new FakeChatModel();
        AssistantPrompts prompts = TestPrompts.prompts("v2");

        ChatClient.builder(model)
                .build()
                .prompt()
                .system(prompts::applySystem)
                .user("在吗")
                .call()
                .content();

        String system = model.lastPrompt().getSystemMessage().getText();
        assertThat(system)
                .contains("「示例商城」")
                .contains("「小示」")
                .contains("今天是2026年11月5日 星期四")
                .contains(TestPrompts.SUPPORT_SCOPE)
                .doesNotContain("{")
                .doesNotContain("}");
    }

    @Test
    void v2AddsStricterRulesAndFewShotExamples() {
        FakeChatModel model = new FakeChatModel();

        ChatClient.builder(model)
                .build()
                .prompt()
                .system(TestPrompts.prompts("v2")::applySystem)
                .user("在吗")
                .call()
                .content();

        String system = model.lastPrompt().getSystemMessage().getText();
        assertThat(system).contains("只用简体中文").contains("绝不编造").contains("示例：");
    }

    /** 第 3 章：v3 让模型先调工具查，订单、商品信息只能来自工具或查询结果，没查到就照实说，绝不编造。v3 刻意写得很短：长提示词会让小模型不调工具（见 docs/ch03.md）。 */
    @Test
    void v3RequiresFactsFromLookupResults() {
        FakeChatModel model = new FakeChatModel();

        ChatClient.builder(model)
                .build()
                .prompt()
                .system(TestPrompts.prompts("v3")::applySystem)
                .user("在吗")
                .call()
                .content();

        String system = model.lastPrompt().getSystemMessage().getText();
        assertThat(system)
                .contains("今天是2026年11月5日 星期四")
                .contains("调用工具查询")
                .contains("只能来自工具结果或「查询结果」")
                .contains("found 为 false")
                .contains("绝不编造订单、库存、价格和物流信息")
                .doesNotContain("{");
    }

    @Test
    void refusalNamesShopAndScope() {
        String refusal = TestPrompts.prompts("v2").refusal();

        assertThat(refusal).contains("示例商城").contains(TestPrompts.SUPPORT_SCOPE).doesNotContain("{");
    }
}
