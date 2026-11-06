package io.github.clarence404.assistant.tool;

import io.github.clarence404.assistant.mall.MallApiClient;
import io.github.clarence404.assistant.mall.MallModels;
import io.github.clarence404.assistant.mall.MallResult;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 智能客服能调用的两个工具：查订单 {@link #queryOrderStatus}、查商品 {@link #queryProduct}。
 *
 * <p>所在层：工具（第 3 章引入）。上游是 Spring AI 的工具调用循环（模型决定调用时）和 {@code ChatService} 的「按意图直接查询」；
 * 下游是 {@link MallApiClient}。
 *
 * <p><b>工具调用（Function Calling / Tool Calling）是什么</b>：模型本身只会生成文字，查不了数据库、调不了接口。工具调用是一个约定：
 *
 * <ol>
 *   <li>请求模型时，除了消息，再附上一份「工具清单」：每个工具的名字、用途说明、参数的 JSON Schema。Spring AI 从这里的
 *       {@link Tool @Tool}（名字和说明）与 {@link ToolParam @ToolParam}（参数说明）自动生成这份清单
 *   <li>模型读了用户的问题和工具说明，<b>自己判断</b>要不要调、调哪个、参数填什么。要调时它不输出回答，而是输出一段「调用意图」：
 *       工具名 + JSON 参数（如 {@code queryOrderStatus {"orderNo":"2118..."}}）
 *   <li>模型从不自己执行任何东西。Spring AI（{@code ToolCallingAdvisor}）收到调用意图后，在我们的进程里执行对应的 Java 方法，
 *       把返回值转成 JSON，作为一条「工具结果」消息（role=tool）追加到对话里，<b>再请求一次模型</b>
 *   <li>模型这次看到了查询结果，据此生成给用户的回答。一次带工具的回答因此至少是两次模型调用
 * </ol>
 *
 * 所以这里的 {@code description} 不是写给人看的注释，而是给模型看的提示词：模型只凭这几句话决定什么时候调用、参数怎么填。
 * 写清楚「做什么、什么时候用、什么时候不要用、参数长什么样」。
 *
 * <p><b>令牌为什么走 {@link ToolContext}</b>：查订单需要用户的商城令牌，但令牌不能让模型看到，也不能让模型填（模型能填的参数，
 * 用户就能用提示注入改掉，比如「帮我查一下订单，token 用 xxx」）。Spring AI 的 ToolContext 是「只给工具、不给模型」的旁路：
 * {@code ChatClient...toolContext(map)} 放进去的东西不会出现在工具清单和任何消息里，执行工具时作为方法参数传进来。
 * 方法签名里的 ToolContext 参数也不会出现在参数 Schema 里。注意 Spring AI 2.0 的一个规定：方法声明了 ToolContext 参数，
 * 调用时就必须给一个<b>非空</b>的 ToolContext，否则直接报「ToolContext is required by the method as an argument」；
 * 所以没登录时也要放一个 {@link #ANONYMOUS} 标记进去（见 {@link #context}），不需要令牌的 {@link #queryProduct} 干脆不声明这个参数。
 *
 * <p><b>工具不抛异常</b>：参数不合法、没登录、查不到、商城挂了，都返回 {@code found=false} + 原因（见 {@link OrderLookup}），让模型照实说。
 */
@Component
public class MallTools {

    /** ToolContext 里存放商城访问令牌的键。 */
    public static final String MALL_TOKEN = "mallToken";

    /** 没登录时 ToolContext 里放的标记（值为 true），保证 ToolContext 非空，见类注释。 */
    public static final String ANONYMOUS = "anonymous";

    /** 订单号：19 位雪花 ID，或 19 位 + 4 位用户基因共 23 位（与 IntentResult 的校验一致）。 */
    static final Pattern ORDER_NO = Pattern.compile("\\d{19}|\\d{23}");

    /** 商品编号：long 型 ID。 */
    static final Pattern SKU_ID = Pattern.compile("\\d{1,19}");

    static final String NOT_LOGGED_IN = "还没有登录，查不了订单：请先在页面右上角登录商城账号";
    static final String LOGIN_EXPIRED = "登录已过期或无效，请重新登录后再查";
    static final String MALL_UNAVAILABLE = "商城服务暂时不可用，请稍后再试";

    private static final Logger log = LoggerFactory.getLogger(MallTools.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MallApiClient mallApi;
    private final List<ToolCallback> callbacks;

    /**
     * 创建工具，并用 {@link ToolCallbacks#from} 把带 {@code @Tool} 的方法包成 Spring AI 的 {@link ToolCallback}。
     *
     * @param mallApi 商城客户端
     */
    public MallTools(MallApiClient mallApi) {
        this.mallApi = mallApi;
        // ToolCallback = 工具定义（名字、说明、参数 Schema）+ 执行入口（JSON 参数 → 调方法 → 返回值转 JSON）。
        // 按名字排序：ToolCallbacks.from 靠反射找方法，JVM 不保证方法的先后顺序，每次启动工具清单的顺序都可能不同；
        // 顺序变了，发给模型的提示词就变了，小模型的决定可能跟着变，录制回放的指纹也对不上
        this.callbacks = Arrays.stream(ToolCallbacks.from(this))
                .sorted(Comparator.comparing(c -> c.getToolDefinition().name()))
                .toList();
    }

    /**
     * 全部工具：注册给模型（原生工具调用）时用。
     *
     * @return 两个工具的 ToolCallback，不可修改
     */
    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    /**
     * 按名字取一个工具：「按意图直接查询」时由代码直接执行，走的是和模型调用时同一个入口。
     *
     * @param name 工具名，即方法名，如 queryOrderStatus
     * @return 对应的 ToolCallback
     * @throws IllegalArgumentException 没有这个工具
     */
    public ToolCallback callback(String name) {
        return callbacks.stream()
                .filter(c -> c.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有名为 " + name + " 的工具"));
    }

    /**
     * 本次请求的 ToolContext 内容：有令牌时是 {@code {mallToken: 令牌}}；没有时是 {@code {anonymous: true}}（工具会返回「请先登录」）。
     *
     * @param mallToken 商城访问令牌，可以为 null
     * @return 交给 {@code ChatClient...toolContext(...)} 或 {@code new ToolContext(...)} 的 Map，从不为空
     */
    public static Map<String, Object> context(String mallToken) {
        return mallToken == null ? Map.of(ANONYMOUS, true) : Map.of(MALL_TOKEN, mallToken);
    }

    /**
     * 查当前登录用户自己的一笔订单。
     *
     * <p>步骤：校验订单号格式 → 从 ToolContext 取令牌（没有就返回「请先登录」，不发请求）→ 调商城 → 把结果或失败翻成
     * {@link OrderLookup}：状态翻成中文、商品拼成一行摘要、时间格式化，并带上「系统不提供物流轨迹」的说明。
     *
     * @param orderNo 模型从用户消息里抄出来的订单号（也可能是模型编的，所以要校验）
     * @param toolContext Spring AI 执行工具时传入，里面是 {@link #MALL_TOKEN} 或没登录时的 {@link #ANONYMOUS}；代码直接调用这个方法时也可能传 null
     * @return 查询结果，从不抛异常
     */
    @Tool(
            name = "queryOrderStatus",
            description = "查询当前登录用户自己的一笔订单：订单状态、实付金额、商品、下单时间。"
                    + "用户问订单状态、发货了没有、到哪了、什么时候到时使用。"
                    + "必须有用户给出的订单号才能调用；用户没给订单号时不要调用，先请用户提供订单号。"
                    + "只能查到当前登录账号自己的订单，查不到物流轨迹。")
    public OrderLookup queryOrderStatus(
            @ToolParam(description = "订单号：19 位或 23 位数字，从用户消息里原样照抄，不要编造、不要补位") String orderNo, ToolContext toolContext) {
        String no = orderNo == null ? null : orderNo.strip();
        if (no == null || !ORDER_NO.matcher(no).matches()) {
            return OrderLookup.notFound(orderNo, "订单号格式不对：应为 19 位或 23 位数字，请核对订单号");
        }
        String token = token(toolContext);
        if (token == null) {
            return OrderLookup.notFound(no, NOT_LOGGED_IN);
        }
        MallResult<MallModels.Order> result = mallApi.getOrder(token, no);
        if (result.isOk()) {
            MallModels.Order order = result.body();
            return new OrderLookup(
                    true,
                    null,
                    order.orderNo(),
                    OrderStatusText.describe(order.status()),
                    order.payAmount(),
                    summarize(order.items()),
                    order.createdAt() == null ? null : order.createdAt().format(TIME),
                    OrderLookup.NO_LOGISTICS);
        }
        return OrderLookup.notFound(no, orderFailureReason(no, result));
    }

    /**
     * 按商品编号查商品：名称、价格、是否在售、是否有货。商品详情匿名可查，不需要令牌。
     *
     * @param skuId 商品编号
     * @return 查询结果，从不抛异常
     */
    @Tool(
            name = "queryProduct",
            description =
                    "按商品编号（SKU，一串数字，如 20001）查询商品的名称、价格、是否在售、是否有货。" + "用户问某个商品多少钱、有没有货、还卖不卖时使用。用户没给商品编号时不要调用，请用户提供商品编号。")
    public ProductLookup queryProduct(@ToolParam(description = "商品编号 SKU：一串数字，如 20001，从用户消息里原样照抄") String skuId) {
        String id = skuId == null ? null : skuId.strip();
        if (id == null || !SKU_ID.matcher(id).matches()) {
            return ProductLookup.notFound(skuId, "商品编号格式不对：应为一串数字，如 20001");
        }
        MallResult<MallModels.ProductPage> result = mallApi.getProduct(id);
        if (result.isOk()) {
            MallModels.Product product = result.body().product();
            return new ProductLookup(
                    true,
                    null,
                    product.skuId(),
                    product.name(),
                    product.price(),
                    product.onShelf(),
                    result.body().inStock());
        }
        return switch (result.failure()) {
            case NOT_FOUND -> ProductLookup.notFound(id, "没有编号为 " + id + " 的商品");
            case REJECTED -> ProductLookup.notFound(id, "商品编号不合法");
            default -> {
                log.warn("查商品失败：skuId={}，{}，code={}", id, result.failure(), result.code());
                yield ProductLookup.notFound(id, MALL_UNAVAILABLE);
            }
        };
    }

    /** 查订单失败 → 能直接说给用户听的原因。 */
    private static String orderFailureReason(String orderNo, MallResult<?> result) {
        return switch (result.failure()) {
            case NOT_FOUND -> "没有找到订单 " + orderNo + "：订单号不存在，或者不是当前登录账号的订单（只能查自己的订单）";
            case UNAUTHORIZED -> LOGIN_EXPIRED;
            case REJECTED -> "订单号不合法，请核对订单号";
            case UNAVAILABLE -> {
                log.warn("查订单失败：商城不可用，code={}", result.code());
                yield MALL_UNAVAILABLE;
            }
        };
    }

    /** 从 ToolContext 取令牌；没有 ToolContext、或里面没有令牌时返回 null。 */
    static String token(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        return toolContext.getContext().get(MALL_TOKEN) instanceof String token && !token.isBlank() ? token : null;
    }

    /** 商品摘要：「商品名 ×数量」，多件用顿号连接。 */
    static String summarize(List<MallModels.OrderItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return items.stream().map(i -> i.productName() + " ×" + i.quantity()).collect(Collectors.joining("、"));
    }
}
