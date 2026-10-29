package com.commercefoundry.domainlayer.order.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.StockReservationInternalRequest;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.StockReservationInternalResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * product-service internal 재고 선점 ({@code order-flow-guide.md} §3-2). 서비스 토큰을 싣는다({@link InternalFeignClientConfiguration}) —
 * 사용자 {@code Authorization} 은 넘기지 않는다 ({@code security-guide.md} §9-5). 같은 대상의 SKU 조회 클라이언트(catalog)와 {@code name} 이 같아 {@code contextId} 로 나눈다.
 *
 * <p>직접 부르지 않는다 — 서킷 · 예외 변환은 {@code StockReservationInternalResponseSupport} 가 감싸고, application 에는 {@code StockReservationPort} 로만 나간다.
 */
@FeignClient(
    name = "${feign-client.target-services.product-service:product-service}",
    contextId = "stockReservationClient",
    configuration = {InternalFeignClientConfiguration.class, StockReservationClientConfiguration.class})
public interface StockReservationClient {

    /** 주문 아이디가 업무 키다 — 같은 주문 · 같은 항목의 재요청은 기존 선점을 200 으로 돌려준다. 거절은 4xx 봉투의 {@code resultCode}({@code PRODUCT_*})로 온다. */
    @PostMapping("/internal/v1/stock-reservations")
    Response<StockReservationInternalResponse> reserve(@RequestBody StockReservationInternalRequest request);

}
