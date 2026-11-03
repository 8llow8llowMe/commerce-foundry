package com.commercefoundry.domainlayer.order.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.StockRestorationInternalRequest;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.StockRestorationInternalResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * product-service internal 결제 후 재고 복원 (#308 PR-C1, {@code order-flow-guide.md} §3-2 "결제 후 복원"). 서비스 토큰을 싣는다({@link InternalFeignClientConfiguration}).
 * 같은 대상의 다른 클라이언트와 {@code name} 이 같아 {@code contextId} 로 나눈다. 재시도하지 않는 설정은 선점 클라이언트와 같다({@link StockReservationClientConfiguration}).
 *
 * <p>직접 부르지 않는다 — 서킷 · 예외 변환은 {@code StockReservationInternalResponseSupport} 가 감싸고, application 에는 {@code StockRestorationPort} 로만 나간다.
 */
@FeignClient(
    name = "${feign-client.target-services.product-service:product-service}",
    contextId = "stockRestorationClient",
    configuration = {InternalFeignClientConfiguration.class, StockReservationClientConfiguration.class})
public interface StockRestorationClient {

    /** 클레임 아이디가 멱등 키다 — 같은 클레임 · 같은 본문의 재요청은 기존 복원을 200 으로 돌려준다. 거절은 4xx 봉투의 {@code resultCode}({@code PRODUCT_*})로 온다. */
    @PostMapping("/internal/v1/stock-restorations")
    Response<StockRestorationInternalResponse> restore(@RequestBody StockRestorationInternalRequest request);
}
