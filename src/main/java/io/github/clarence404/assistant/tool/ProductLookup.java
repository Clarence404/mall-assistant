package io.github.clarence404.assistant.tool;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 工具 {@code queryProduct} 的返回值，转成 JSON 交回给模型。查不到时同样是结构化结果，见 {@link OrderLookup} 的说明。
 *
 * <p>所在层：工具（第 3 章引入）。
 *
 * @param found 是否查到
 * @param reason 没查到的原因；查到时为 null
 * @param skuId 商品编号
 * @param name 商品名
 * @param price 价格（元）
 * @param onShelf 是否在售：下架的商品能查到，但买不了
 * @param inStock 是否有货：商城详情页的库存提示，以下单时为准
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductLookup(
        boolean found, String reason, String skuId, String name, String price, Boolean onShelf, Boolean inStock) {

    /**
     * 没查到。
     *
     * @param skuId 用户给的商品编号（可能为 null）
     * @param reason 原因
     * @return found=false 的结果
     */
    public static ProductLookup notFound(String skuId, String reason) {
        return new ProductLookup(false, reason, skuId, null, null, null, null);
    }
}
