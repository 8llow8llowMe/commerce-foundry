package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.application.port.out.query.SkuRestoredQuantityQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.StockRestoration;
import java.util.List;
import java.util.Optional;

/**
 * 결제 후 재고 복원 기록 (헤더 + 항목). append-only 다 — 만들고 고치지 않는다.
 */
public interface StockRestorationRepositoryPort {

    /** 클레임의 복원 — {@code uk_stock_restoration_claim_id} 로 찾는다. 헤더와 항목(SKU 아이디 오름차순)을 함께 읽는다. 쿼리 2번이다. */
    Optional<StockRestoration> findByClaimId(long claimId);

    /**
     * 선점 하나에서 SKU 별로 이미 복원한 수량 합. group by 한 번(쿼리 1번)이다. 복원한 적 없는 SKU 는 결과에 행이 없다 — 0 으로 본다.
     * 순서는 정하지 않는다.
     */
    List<SkuRestoredQuantityQueryResult> sumRestoredQuantitiesByReservationId(long reservationId);

    /**
     * 헤더와 항목을 INSERT 하고 바로 flush 한다. 같은 클레임의 복원이 그 사이 생겼으면(동시 생성) {@code STOCK_RESTORATION_CONFLICT}(409) 다.
     */
    void create(StockRestoration restoration);
}
