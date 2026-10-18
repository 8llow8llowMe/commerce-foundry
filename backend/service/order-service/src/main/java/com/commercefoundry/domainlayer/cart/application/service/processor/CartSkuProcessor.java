package com.commercefoundry.domainlayer.cart.application.service.processor;

import com.commercefoundry.domainlayer.cart.application.exception.CartErrorCode;
import com.commercefoundry.domainlayer.cart.application.exception.CartException;
import com.commercefoundry.domainlayer.cart.application.model.CartSku;
import com.commercefoundry.domainlayer.cart.application.port.out.SkuQueryPort;
import com.commercefoundry.domainlayer.cart.application.port.out.query.SkuQueryResult;
import com.commercefoundry.domainlayer.cart.domain.model.CartItem;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * product-service 의 현재 SKU 를 읽는다 — 원격 호출이라 트랜잭션을 걸지 않는다. 몇 개를 읽든 호출은 한 번이다 ({@code coding-conventions.md} §9-6).
 */
@Component
@RequiredArgsConstructor
public class CartSkuProcessor {

    private final SkuQueryPort skuQueryPort;

    /**
     * @return SKU 아이디 → SKU. 없는 SKU 는 키가 없다. 빈 컬렉션이면 호출하지 않는다
     */
    public Map<Long, CartSku> findSkus(Collection<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, CartSku> skus = new LinkedHashMap<>();
        for (SkuQueryResult result : skuQueryPort.findSkus(skuIds)) {
            skus.put(result.skuId(), toModel(result));
        }
        return skus;
    }

    public Optional<CartSku> findSku(long skuId) {
        return Optional.ofNullable(findSkus(List.of(skuId)).get(skuId));
    }

    /**
     * 이미 담긴 줄의 현재 수량 상한 — {@link CartSku#maxQuantity()}. SKU 를 찾지 못하면(삭제 등) {@link CartItem#MAX_QUANTITY} 다 —
     * 상한 정보가 없을 뿐 사용자가 줄을 고치거나 지우는 것은 막지 않는다.
     */
    public int maxQuantityOf(long skuId) {
        return findSku(skuId).map(CartSku::maxQuantity).orElse(CartItem.MAX_QUANTITY);
    }

    /**
     * 새로 담을 SKU. 아래 순서로 거절한다.
     *
     * <ol>
     *   <li>없는 SKU — {@code CART_SKU_NOT_FOUND}(404)</li>
     *   <li>품절 · 판매 종료 · 숨김 — {@code CART_SKU_NOT_PURCHASABLE}(409)</li>
     *   <li>맞춤 제작 상품이 아닌데 맞춤 제작 입력이 있다 — {@code CART_CUSTOM_OPTION_NOT_ALLOWED}(400)</li>
     * </ol>
     */
    public CartSku getAddableSku(long skuId, boolean hasCustomOptions) {
        CartSku sku = findSku(skuId).orElseThrow(() -> new CartException(CartErrorCode.CART_SKU_NOT_FOUND));
        if (!sku.purchasable()) {
            throw new CartException(CartErrorCode.CART_SKU_NOT_PURCHASABLE);
        }
        if (!sku.accepts(hasCustomOptions)) {
            throw new CartException(CartErrorCode.CART_CUSTOM_OPTION_NOT_ALLOWED);
        }
        return sku;
    }

    private static CartSku toModel(SkuQueryResult result) {
        return new CartSku(
            result.skuId(), result.productId(), result.productName(), result.optionName(), result.salePrice(),
            result.purchasable(), result.soldOut(), result.maxQuantityPerOrder(), result.customOrder());
    }
}
