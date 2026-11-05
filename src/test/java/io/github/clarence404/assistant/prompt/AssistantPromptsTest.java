package io.github.clarence404.assistant.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.assistant.chat.FakeChatModel;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/** 第 2 章：系统提示词模板——按版本选文件、渲染变量，以及拒答话术。 */
class AssistantPromptsTest {

    @Test
    void versionPropertySelectsTemplateFile() {
        assertThat(TestPrompts.prompts("v2").systemTemplate().getFilename()).isEqualTo("system-v2.st");
        assertThat(TestPrompts.prompts("v1").systemTemplate().getFilename()).isEqualTo("system-v1.st");
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

    @Test
    void refusalNamesShopAndScope() {
        String refusal = TestPrompts.prompts("v2").refusal();

        assertThat(refusal).contains("示例商城").contains(TestPrompts.SUPPORT_SCOPE).doesNotContain("{");
    }
}
