package io.github.clarence404.assistant.aftersales;

import io.github.clarence404.assistant.mall.MallToken;
import io.github.clarence404.assistant.memory.ConversationOwner;
import io.github.clarence404.assistant.memory.ConversationOwners;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查看自己的售后申请单：{@code GET /api/agent/after-sales}（第 7 章引入，调试与冒烟用）。
 *
 * <p>所在层：接口层。归属和第 6 章一样问商城确认（{@link ConversationOwners#resolve}）：只列出当前登录用户自己的；没登录返回空列表；
 * 令牌无效 401。
 */
@RestController
public class AfterSalesController {

    private final AfterSalesStore store;
    private final ConversationOwners owners;

    /**
     * 创建。
     *
     * @param store 售后申请单存储
     * @param owners 归属
     */
    public AfterSalesController(AfterSalesStore store, ConversationOwners owners) {
        this.store = store;
        this.owners = owners;
    }

    /**
     * 我的售后申请单，从新到旧。
     *
     * @param authorization {@code Authorization: Bearer <商城令牌>}，没登录时不带
     * @return 申请单
     */
    @GetMapping("/api/agent/after-sales")
    public List<AfterSalesApplication> mine(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        ConversationOwner owner = owners.resolve(MallToken.fromAuthorizationHeader(authorization));
        return owner.kind() == ConversationOwner.Kind.ANONYMOUS ? List.of() : store.findByOwner(owner.key());
    }
}
