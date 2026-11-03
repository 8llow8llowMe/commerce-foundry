package com.commercefoundry.domainlayer.order.adapter.out.client;

import com.commercefoundry.domainlayer.order.adapter.out.client.feign.StockReservationClient;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.StockReservationInternalRequest;
import com.commercefoundry.domainlayer.order.adapter.out.client.support.StockReservationInternalResponseSupport;
import com.commercefoundry.domainlayer.order.application.port.out.StockReservationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockConfirmationQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import java.util.SortedMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockReservationClientAdapter implements StockReservationPort {

    private final StockReservationClient stockReservationClient;
    private final StockReservationInternalResponseSupport stockReservationInternalResponseSupport;

    /** 수량 합계를 SKU 아이디 오름차순 그대로 옮긴다 — 같은 SKU 를 합치는 일은 부른 Processor 가 했다. */
    @Override
    public StockReservationQueryResult reserve(long orderId, SortedMap<Long, Integer> quantitiesBySkuId) {
        StockReservationInternalRequest request = toRequest(orderId, quantitiesBySkuId);
        return stockReservationInternalResponseSupport.toQueryResult(
            stockReservationInternalResponseSupport.call(() -> stockReservationClient.reserve(request)), orderId, quantitiesBySkuId);
    }

    /** 같은 선점 엔드포인트 · 같은 요청 모양이다 — 응답에서 같은 주문의 확정된 선점(CONFIRMED)도 받는 것만 다르다. */
    @Override
    public StockReservationQueryResult reReserve(long orderId, SortedMap<Long, Integer> quantitiesBySkuId) {
        StockReservationInternalRequest request = toRequest(orderId, quantitiesBySkuId);
        return stockReservationInternalResponseSupport.toReReservationQueryResult(
            stockReservationInternalResponseSupport.call(() -> stockReservationClient.reserve(request)), orderId, quantitiesBySkuId);
    }

    @Override
    public StockConfirmationQueryResult confirm(long reservationId) {
        if (reservationId <= 0) {
            throw new IllegalArgumentException("positive reservationId required");
        }
        return stockReservationInternalResponseSupport.toConfirmationQueryResult(
            stockReservationInternalResponseSupport.callConfirmation(() -> stockReservationClient.confirm(reservationId)), reservationId);
    }

    @Override
    public void release(long reservationId, long orderId) {
        if (reservationId <= 0) {
            throw new IllegalArgumentException("positive reservationId required");
        }
        stockReservationInternalResponseSupport.requireReleased(
            stockReservationInternalResponseSupport.callRelease(() -> stockReservationClient.release(reservationId)), reservationId, orderId);
    }

    private static StockReservationInternalRequest toRequest(long orderId, SortedMap<Long, Integer> quantitiesBySkuId) {
        return new StockReservationInternalRequest(orderId,
            quantitiesBySkuId.entrySet().stream().map(entry -> new StockReservationInternalRequest.Item(entry.getKey(), entry.getValue())).toList());
    }
}
