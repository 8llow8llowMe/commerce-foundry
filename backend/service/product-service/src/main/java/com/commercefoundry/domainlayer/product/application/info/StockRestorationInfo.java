package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.domain.model.StockRestoration;
import com.commercefoundry.domainlayer.product.domain.model.StockRestorationReason;
import java.util.List;

/**
 * 재고 복원 결과 — 신규 · 멱등 재요청 모두 같은 모양이다.
 *
 * @param items SKU 아이디 오름차순
 */
public record StockRestorationInfo(long restorationId, long claimId, long reservationId, StockRestorationReason reason, List<Item> items) {

    public static StockRestorationInfo from(StockRestoration restoration) {
        return new StockRestorationInfo(
            restoration.id(), restoration.claimId(), restoration.reservationId(), restoration.reason(),
            restoration.items().stream().map(item -> new Item(item.skuId(), item.quantity(), item.restored())).toList()
        );
    }

    /** @param restored 재고를 실제로 되돌렸는가. 무제한 SKU 는 false (기록만) */
    public record Item(long skuId, int quantity, boolean restored) {
    }
}
