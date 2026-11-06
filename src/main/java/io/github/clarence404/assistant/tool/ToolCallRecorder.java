package io.github.clarence404.assistant.tool;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 记录一次对话请求里的全部工具调用（名字、参数、结果、耗时），每次请求新建一个。
 *
 * <p>所在层：工具（第 3 章引入）。上游是 {@code ChatService}：它把 {@link MallTools#callbacks()} 用 {@link #wrap} 包一层再注册给模型，
 * 「按意图直接查询」时也经 {@link #invoke} 执行；记录结果放进 {@code ChatReply.tools} 与流式的 {@code tool} 事件。
 *
 * <p>做法是装饰器：包装后的 {@link ToolCallback} 对 Spring AI 来说和原来的一模一样（同样的名字、说明、参数 Schema），
 * 只是执行时先计时、执行完记一笔。这样不用改工具本身，也不用依赖框架的内部日志，就能看到「模型到底调了什么、传了什么参数」——
 * 调试工具调用时这是最有用的信息：模型没调工具、调错了工具、参数抄错了，一眼就能看出来。
 *
 * <p>线程：流式回答时工具在 Reactor 的线程上执行，页面请求在另一个线程上读记录，所以列表用 {@link CopyOnWriteArrayList}。
 */
public final class ToolCallRecorder {

    private final List<ToolInvocation> invocations = new CopyOnWriteArrayList<>();
    private int drained;

    /**
     * 把工具包一层，执行时自动记录，来源记为 {@link ToolInvocation.Source#MODEL}（模型决定调用）。
     *
     * @param callbacks 原始工具
     * @return 包装后的工具，名字、说明、参数 Schema 不变
     */
    public List<ToolCallback> wrap(List<ToolCallback> callbacks) {
        return callbacks.stream().<ToolCallback>map(Recording::new).toList();
    }

    /**
     * 执行一个工具并记录：计时 → {@code callback.call(arguments, toolContext)} → 记下名字、来源、参数、结果、耗时 → 返回结果。
     * 工具抛异常时也记一笔（结果写「执行失败：…」），再把异常原样抛出。
     *
     * @param callback 要执行的工具
     * @param arguments JSON 参数，如 {@code {"orderNo":"..."}}
     * @param toolContext 交给工具的上下文（含令牌），可以为 null
     * @param source 谁决定调用的
     * @return 工具结果（JSON 字符串），即交给模型的内容
     */
    public String invoke(
            ToolCallback callback, String arguments, ToolContext toolContext, ToolInvocation.Source source) {
        // TODO ch03-4：用 System.nanoTime() 计时，执行 callback.call(arguments, toolContext)，然后往 invocations 里加一条
        //  new ToolInvocation(工具名, source, arguments, 结果, 耗时毫秒)，返回结果。工具名取 callback.getToolDefinition().name()。
        //  工具抛 RuntimeException 时也要记一条（结果写「执行失败：」+ 异常信息），再把异常原样抛出。
        //  验收：ToolCallRecorderTest
        return callback.call(arguments, toolContext);
    }

    /**
     * 到目前为止的全部记录，按调用顺序。
     *
     * @return 不可修改的副本
     */
    public List<ToolInvocation> invocations() {
        return List.copyOf(invocations);
    }

    /**
     * 取出上次取过之后新增的记录：流式接口在推每段文字之前调一次，把新发生的工具调用先推给页面。
     *
     * @return 新增的记录，可能为空
     */
    public synchronized List<ToolInvocation> drainNew() {
        List<ToolInvocation> fresh = List.copyOf(invocations.subList(drained, invocations.size()));
        drained += fresh.size();
        return fresh;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 包装后的工具：定义照搬，执行时经 {@link #invoke} 记录。 */
    private final class Recording implements ToolCallback {

        private final ToolCallback delegate;

        Recording(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return invoke(delegate, toolInput, null, ToolInvocation.Source.MODEL);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return invoke(delegate, toolInput, toolContext, ToolInvocation.Source.MODEL);
        }
    }
}
