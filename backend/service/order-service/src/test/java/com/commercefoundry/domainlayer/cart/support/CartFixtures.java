package com.commercefoundry.domainlayer.cart.support;

import com.commercefoundry.domainlayer.cart.application.command.CartItemAddCommand;
import com.commercefoundry.domainlayer.cart.application.model.CartSku;
import com.commercefoundry.domainlayer.cart.application.port.out.query.SkuQueryResult;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOption;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOptions;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;

public final class CartFixtures {

    public static final long MEMBER_ID = 4611686018427387904L;
    public static final long OTHER_MEMBER_ID = 4611686018427387905L;

    private CartFixtures() {
    }

    public static Money krw(long amount) {
        return Money.of(amount, CurrencyCode.KRW);
    }

    /** 구매 가능 · 제한 없음 · 일반 상품. */
    public static SkuQueryResult onSale(long skuId, long price) {
        return new SkuQueryResult(skuId, skuId + 7000, "상품" + skuId, "옵션" + skuId, krw(price), true, false, null, false);
    }

    public static SkuQueryResult soldOut(long skuId, long price) {
        return new SkuQueryResult(skuId, skuId + 7000, "상품" + skuId, "옵션" + skuId, krw(price), false, true, null, false);
    }

    public static SkuQueryResult stopSelling(long skuId, long price) {
        return new SkuQueryResult(skuId, skuId + 7000, "상품" + skuId, "옵션" + skuId, krw(price), false, false, null, false);
    }

    public static SkuQueryResult withMaxQuantity(SkuQueryResult sku, Integer maxQuantityPerOrder) {
        return new SkuQueryResult(sku.skuId(), sku.productId(), sku.productName(), sku.optionName(), sku.salePrice(),
            sku.purchasable(), sku.soldOut(), maxQuantityPerOrder, sku.customOrder());
    }

    public static SkuQueryResult customOrder(SkuQueryResult sku) {
        return new SkuQueryResult(sku.skuId(), sku.productId(), sku.productName(), sku.optionName(), sku.salePrice(),
            sku.purchasable(), sku.soldOut(), sku.maxQuantityPerOrder(), true);
    }

    public static CartSku cartSku(SkuQueryResult sku) {
        return new CartSku(sku.skuId(), sku.productId(), sku.productName(), sku.optionName(), sku.salePrice(),
            sku.purchasable(), sku.soldOut(), sku.maxQuantityPerOrder(), sku.customOrder());
    }

    public static CartItemAddCommand add(long skuId, int quantity) {
        return new CartItemAddCommand(skuId, quantity, CustomOptions.empty());
    }

    public static CartItemAddCommand add(long skuId, int quantity, CustomOptions customOptions) {
        return new CartItemAddCommand(skuId, quantity, customOptions);
    }

    public static CustomOptions options(String fieldKey, String value) {
        return CustomOptions.of(List.of(CustomOption.of(fieldKey, value)));
    }
}
