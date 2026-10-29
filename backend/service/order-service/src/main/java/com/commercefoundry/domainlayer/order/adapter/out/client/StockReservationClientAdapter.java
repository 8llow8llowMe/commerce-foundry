package com.commercefoundry.domainlayer.order.adapter.out.client;

import com.commercefoundry.domainlayer.order.adapter.out.client.feign.StockReservationClient;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.StockReservationInternalRequest;
import com.commercefoundry.domainlayer.order.adapter.out.client.support.StockReservationInternalResponseSupport;
import com.commercefoundry.domainlayer.order.application.port.out.StockReservationPort;
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
        StockReservationInternalRequest request = new StockReservationInternalRequest(orderId,
            quantitiesBySkuId.entrySet().stream().map(entry -> new StockReservationInternalRequest.Item(entry.getKey(), entry.getValue())).toList());
        return stockReservationInternalResponseSupport.toQueryResult(
            stockReservationInternalResponseSupport.call(() -> stockReservationClient.reserve(request)), orderId, quantitiesBySkuId);
    }

}
