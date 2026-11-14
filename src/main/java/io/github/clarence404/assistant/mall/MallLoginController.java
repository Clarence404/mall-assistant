package io.github.clarence404.assistant.mall;

import io.github.clarence404.assistant.guard.CallerResolver;
import io.github.clarence404.assistant.guard.ChatGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 页面登录的代理接口：{@code POST /api/mall/login}，把手机号和密码转给商城网关的 {@code /api/auth/login}，只把访问令牌还给页面。
 *
 * <p>所在层：接口层（第 3 章引入）。上游是 {@code static/index.html} 的登录框；下游是 {@link MallApiClient#login}。
 *
 * <p>为什么要经智能客服转一道，而不是页面直接调 9000：页面是从 8088 打开的，直接调 9000 属于跨域，要商城那边开 CORS；
 * 转一道之后页面只和 8088 打交道。令牌回到页面后，由页面在每次提问时放进 {@code Authorization} 请求头带回来，
 * 智能客服本身不保存任何人的登录状态（没有会话），重启也不影响。刷新令牌不往外给：页面用不上，少暴露一样东西。
 *
 * <p>审查修复：登录代理原来不限次数，等于替商城开了一个可以随便试密码的口子（商城自己的登录限流是按它看到的来源算的，而它看到的来源
 * 永远是智能客服这台机器）。现在先过 {@link ChatGuard#throttle}：按来源 IP，每分钟次数和对话接口共用一个上限。
 */
@RestController
public class MallLoginController {

    private final MallApiClient mallApi;
    private final ChatGuard guard;
    private final CallerResolver callers;

    /**
     * 创建登录代理接口。
     *
     * @param mallApi 商城客户端
     * @param guard 限流（审查修复）
     * @param callers 这次请求算谁的
     */
    public MallLoginController(MallApiClient mallApi, ChatGuard guard, CallerResolver callers) {
        this.mallApi = mallApi;
        this.guard = guard;
        this.callers = callers;
    }

    /**
     * 登录。
     *
     * @param request 手机号与密码，密码不记日志
     * @param http 取来源地址（按 IP 限流）
     * @return 200 {@code {"accessToken": "...", "expiresIn": 1800}}；手机号或密码错 401；商城不可用 503；均为 ProblemDetail，带 code
     * @throws io.github.clarence404.assistant.guard.ChatRejection 太频繁（429 RATE_LIMITED）
     */
    @PostMapping("/api/mall/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        guard.throttle(callers.resolve(null, http.getRemoteAddr()));
        MallResult<MallModels.Tokens> result = mallApi.login(request.phone(), request.password());
        if (result.isOk()) {
            return ResponseEntity.ok(
                    new LoginReply(result.body().accessToken(), result.body().expiresIn()));
        }
        if (result.failure() == MallFailure.UNAUTHORIZED) {
            return problem(HttpStatus.UNAUTHORIZED, "MALL_LOGIN_FAILED", "手机号或密码错误");
        }
        if (result.failure() == MallFailure.REJECTED) {
            return problem(HttpStatus.BAD_REQUEST, "MALL_LOGIN_REJECTED", "登录请求不合法，请检查手机号和密码");
        }
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "MALL_UNAVAILABLE", "商城服务暂时不可用，请确认 mall-evolution 已启动后再试");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("商城登录失败");
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }

    /**
     * 登录请求。
     *
     * @param phone 手机号
     * @param password 密码
     */
    public record LoginRequest(
            @NotBlank @Size(max = 32) String phone,
            @NotBlank @Size(max = 64) String password) {}

    /**
     * 登录成功的响应。
     *
     * @param accessToken 商城访问令牌，页面保存在 sessionStorage，提问时放进 Authorization 请求头
     * @param expiresIn 有效期，秒（商城默认 30 分钟）
     */
    public record LoginReply(String accessToken, long expiresIn) {}
}
