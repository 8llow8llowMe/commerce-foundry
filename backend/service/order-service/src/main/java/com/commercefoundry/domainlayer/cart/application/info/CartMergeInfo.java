package com.commercefoundry.domainlayer.cart.application.info;

import com.commercefoundry.domainlayer.cart.application.model.CartMergeSkip;
import java.util.List;

/**
 * 병합 결과.
 *
 * @param cart 병합 후 장바구니
 * @param skippedItems 담지 못하고 건너뛴 항목 — 요청 순서
 */
public record CartMergeInfo(CartInfo cart, List<CartMergeSkip> skippedItems) {

    public CartMergeInfo {
        skippedItems = List.copyOf(skippedItems);
    }
}
