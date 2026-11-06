package io.github.clarence404.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.assistant.mall.StubMallApi;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 第 3 章：页面登录的代理接口 {@code POST /api/mall/login}（{@code MallLoginController}，已给好，不用写）。
 *
 * <p>验证：账号密码转给商城，只把访问令牌还给页面（刷新令牌不往外给）；密码错 401、商城挂了 503，都是带 code 的 ProblemDetail；
 * 空手机号直接 400，不发请求。手法同 {@link FakeModelWebTest}，商城是假网关。
 */
class MallLoginApiTest extends FakeModelWebTest {

    @Test
    void loginReturnsAccessTokenOnly() throws Exception {
        login("{\"phone\":\"13800000000\",\"password\":\"mall123\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value(StubMallApi.TOKEN))
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void wrongPasswordIs401() throws Exception {
        login("{\"phone\":\"13800000000\",\"password\":\"wrong\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MALL_LOGIN_FAILED"));
    }

    @Test
    void mallDownIs503() throws Exception {
        MALL.failWith(500);

        login("{\"phone\":\"13800000000\",\"password\":\"mall123\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MALL_UNAVAILABLE"));
    }

    @Test
    void blankPhoneIsRejectedWithoutCallingMall() throws Exception {
        login("{\"phone\":\"\",\"password\":\"mall123\"}").andExpect(status().isBadRequest());

        assertThat(MALL.requests()).isEmpty();
    }

    private ResultActions login(String body) throws Exception {
        return mockMvc.perform(
                post("/api/mall/login").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
