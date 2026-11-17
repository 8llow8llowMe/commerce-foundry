package com.commercefoundry.domainlayer.order.adapter.out.client;

import com.commercefoundry.domainlayer.order.adapter.out.client.feign.StockRestorationClient;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.StockRestorationInternalRequest;
import com.commercefoundry.domainlayer.order.adapter.out.client.support.StockReservationInternalResponseSupport;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import java.util.SortedMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockRestorationClientAdapter implements StockRestorationPort {

    /** product-service {@code StockRestorationReason} 의 결제 후 취소 값 — 원격 enum 을 가져다 쓰지 않고 이름만 맞춘다. */
    static final String CANCELLATION = "CANCELLATION";

    /** product-service {@code StockRestorationReason} 의 반품 값 (#17 B2a). */
    static final String RETURN = "RETURN";

    private final StockRestorationClient stockRestorationClient;
    private final StockReservationInternalResponseSupport stockReservationInternalResponseSupport;

    /** 수량 합계를 SKU 아이디 오름차순 그대로 옮긴다 — 같은 SKU 를 합치는 일은 부른 Processor 가 했다. */
    @Override
    public void restoreCancellation(long claimId, long reservationId, SortedMap<Long, Integer> quantitiesBySkuId) {
        restore(claimId, reservationId, CANCELLATION, quantitiesBySkuId);
    }

    @Override
    public void restoreReturn(long claimId, long reservationId, SortedMap<Long, Integer> quantitiesBySkuId) {
        restore(claimId, reservationId, RETURN, quantitiesBySkuId);
    }

    private void restore(long claimId, long reservationId, String reason, SortedMap<Long, Integer> quantitiesBySkuId) {
        if (claimId <= 0 || reservationId <= 0 || quantitiesBySkuId.isEmpty()) {
            throw new IllegalArgumentException("positive claimId, reservationId and items required");
        }
        StockRestorationInternalRequest request = new StockRestorationInternalRequest(claimId, reservationId, reason,
            quantitiesBySkuId.entrySet().stream().map(entry -> new StockRestorationInternalRequest.Item(entry.getKey(), entry.getValue())).toList());
        stockReservationInternalResponseSupport.requireRestored(
            stockReservationInternalResponseSupport.callRestoration(() -> stockRestorationClient.restore(request)), claimId, reservationId, reason,
            quantitiesBySkuId);
    }
}
