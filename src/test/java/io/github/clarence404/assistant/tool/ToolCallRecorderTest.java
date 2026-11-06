package io.github.clarence404.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 第 3 章：工具调用记录器 {@link ToolCallRecorder}。
 *
 * <p>验证：包装后的工具定义不变（模型看到的还是同一个工具）；每次执行都记下名字、来源、参数、结果、耗时，ToolContext 原样传给工具；
 * 工具抛异常也留下记录、异常照常抛出；{@link ToolCallRecorder#drainNew()} 每次只给出新增的记录（流式接口靠它把工具事件插在文字前面）。
 *
 * <p>手法：用一个手写的 {@link ToolCallback}（{@link EchoTool}）代替真实工具，不涉及网络和模型。
 */
class ToolCallRecorderTest {

    @Test
    void wrappedCallbackKeepsDefinitionAndRecordsModelCalls() {
        ToolCallRecorder recorder = new ToolCallRecorder();
        EchoTool echo = new EchoTool();

        ToolCallback wrapped = recorder.wrap(List.of(echo)).getFirst();
        String result = wrapped.call("{\"q\":1}", new ToolContext(Map.of("k", "v")));

        assertThat(wrapped.getToolDefinition()).isSameAs(echo.getToolDefinition());
        assertThat(result).isEqualTo("echo:{\"q\":1}");
        assertThat(echo.lastContext.getContext()).containsEntry("k", "v");
        assertThat(recorder.invocations()).singleElement().satisfies(i -> {
            assertThat(i.name()).isEqualTo("echo");
            assertThat(i.source()).isEqualTo(ToolInvocation.Source.MODEL);
            assertThat(i.arguments()).isEqualTo("{\"q\":1}");
            assertThat(i.result()).isEqualTo("echo:{\"q\":1}");
            assertThat(i.durationMs()).isNotNegative();
        });
    }

    @Test
    void directInvocationIsRecordedAsDirect() {
        ToolCallRecorder recorder = new ToolCallRecorder();

        recorder.invoke(new EchoTool(), "{}", null, ToolInvocation.Source.DIRECT);

        assertThat(recorder.invocations())
                .extracting(ToolInvocation::source)
                .containsExactly(ToolInvocation.Source.DIRECT);
    }

    @Test
    void failingToolIsRecordedAndRethrown() {
        ToolCallRecorder recorder = new ToolCallRecorder();
        EchoTool broken = new EchoTool();
        broken.failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> recorder.invoke(broken, "{}", null, ToolInvocation.Source.MODEL))
                .isSameAs(broken.failure);
        assertThat(recorder.invocations())
                .singleElement()
                .satisfies(i -> assertThat(i.result()).contains("执行失败").contains("boom"));
    }

    @Test
    void drainReturnsOnlyNewInvocations() {
        ToolCallRecorder recorder = new ToolCallRecorder();
        EchoTool echo = new EchoTool();

        recorder.invoke(echo, "1", null, ToolInvocation.Source.MODEL);
        assertThat(recorder.drainNew()).hasSize(1);
        assertThat(recorder.drainNew()).isEmpty();
        recorder.invoke(echo, "2", null, ToolInvocation.Source.MODEL);
        recorder.invoke(echo, "3", null, ToolInvocation.Source.MODEL);
        assertThat(recorder.drainNew()).extracting(ToolInvocation::arguments).containsExactly("2", "3");
        assertThat(recorder.invocations()).hasSize(3);
    }

    /** 回显参数的假工具，记下收到的 ToolContext，可以设置成抛异常。 */
    static final class EchoTool implements ToolCallback {

        ToolContext lastContext;
        RuntimeException failure;

        @Override
        public ToolDefinition getToolDefinition() {
            return DEFINITION;
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            lastContext = toolContext;
            if (failure != null) {
                throw failure;
            }
            return "echo:" + toolInput;
        }

        private static final ToolDefinition DEFINITION = ToolDefinition.builder()
                .name("echo")
                .description("回显")
                .inputSchema("{\"type\":\"object\"}")
                .build();
    }
}
