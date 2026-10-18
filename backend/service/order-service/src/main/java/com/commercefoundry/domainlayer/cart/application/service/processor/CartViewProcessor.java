package com.commercefoundry.domainlayer.cart.application.service.processor;

import com.commercefoundry.domainlayer.cart.application.info.CartInfo;
import com.commercefoundry.domainlayer.cart.application.info.CartLineInfo;
import com.commercefoundry.domainlayer.cart.application.model.CartSku;
import com.commercefoundry.domainlayer.cart.domain.model.CartItem;
import com.commercefoundry.domainlayer.cart.domain.model.CartItemStatus;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 저장된 줄과 조회 시점의 SKU 를 묶어 장바구니 조회 결과를 만든다. 가격 · 판매 상태는 저장하지 않고 매번 현재 값을 쓴다 —
 * 담은 뒤 가격이 바뀌거나 품절되면 다음 조회에 그대로 드러난다. I/O 가 없다.
 */
@Component
public class CartViewProcessor {

    /**
     * 합계는 구매 가능하고 수량이 상한 안인 줄만 더한다. 품절 · 판매 중지 · SKU 를 찾지 못한 줄 · 상한을 넘은 줄은 목록에는 남고 합계에서 빠진다.
     *
     * <p>지금 SKU 가격은 KRW 하나라 통화가 섞이지 않는다. 섞이면 {@link Money#plus} 가 실패한다 — USD 가격(해외 판매)이 생기면 통화별 합계로 바꾼다.
     *
     * @param items 최근에 담은 순
     * @param skus SKU 아이디 → 현재 SKU. 찾지 못한 SKU 는 키가 없다
     */
    public CartInfo compose(List<CartItem> items, Map<Long, CartSku> skus) {
        List<CartLineInfo> lines = items.stream().map(item -> toLine(item, skus.get(item.skuId()))).toList();
        List<CartLineInfo> countable = lines.stream().filter(CartLineInfo::countable).toList();
        Money totalAmount = countable.stream().map(CartLineInfo::lineAmount).reduce(Money::plus).orElse(null);
        return new CartInfo(lines, totalAmount, countable.size());
    }

    private static CartLineInfo toLine(CartItem item, CartSku sku) {
        CartLineInfo.CartLineInfoBuilder line = CartLineInfo.builder()
            .itemId(item.id())
            .skuId(item.skuId())
            .quantity(item.quantity())
            .customOptions(item.customOptions().values());
        if (sku == null) {
            // 삭제된 SKU 등. 상품 정보 없이 판매 중지로 보여 주고, 사용자가 지울 수 있게 줄은 남긴다
            return line
                .status(CartItemStatus.resolve(false, false, false))
                .customOrder(false)
                .quantityExceeded(false)
                .build();
        }
        return line
            .productId(sku.productId())
            .productName(sku.productName())
            .optionName(sku.optionName())
            .unitPrice(sku.salePrice())
            .lineAmount(sku.salePrice().times(item.quantity()))
            .status(sku.status())
            .maxQuantityPerOrder(sku.maxQuantityPerOrder())
            .customOrder(sku.customOrder())
            .quantityExceeded(item.exceeds(sku.maxQuantity()))
            .build();
    }
}
