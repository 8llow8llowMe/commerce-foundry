package com.commercefoundry.domainlayer.order.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.ShippingFeeQuoteInternalRequest;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.ShippingFeeQuoteInternalResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * delivery-service internal 배송비 견적. 서비스 토큰을 싣는다({@link InternalFeignClientConfiguration}) — 사용자 {@code Authorization} 은 넘기지 않는다
 * ({@code security-guide.md} §9-5). {@code name} 은 대상의 Eureka 서비스 ID(delivery-service 의 {@code spring.application.name})이자 서비스 토큰의 {@code aud} 다.
 * delivery 는 이 엔드포인트의 허용 호출자로 {@code SERVICE_order-service} 를 선언한다.
 *
 * <p>직접 부르지 않는다 — 서킷 · 예외 변환은 {@code ShippingFeeInternalResponseSupport} 가 감싸고, application 에는 {@code ShippingFeeQueryPort} 로만 나간다.
 */
@FeignClient(
    name = "${feign-client.target-services.delivery-service:delivery-service}",
    contextId = "shippingFeeClient",
    configuration = InternalFeignClientConfiguration.class)
public interface ShippingFeeClient {

    /** 저장하지 않는 계산이라 {@code POST} 지만 200 이다. 거절은 4xx 봉투의 {@code resultCode}({@code DELIVERY_*})로 온다. */
    @PostMapping("/internal/v1/shipping-fees/quotes")
    Response<ShippingFeeQuoteInternalResponse> quote(@RequestBody ShippingFeeQuoteInternalRequest request);
}
