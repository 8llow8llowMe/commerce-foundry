package com.commercefoundry.domainlayer.shipment.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shipment.adapter.out.client.feign.dto.OrderFulfillmentInternalResponse;
import com.commercefoundry.domainlayer.shipment.adapter.out.client.feign.dto.OrderShipmentInternalRequest;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * order-service internal 출고 반영 (#18, {@code api-design-guide.md} §2-18). 서비스 토큰을 싣는다({@link InternalFeignClientConfiguration}) — 사용자 {@code Authorization} 은
 * 넘기지 않는다 ({@code security-guide.md} §9-5). {@code name} 은 대상의 Eureka 서비스 ID 이자 서비스 토큰의 {@code aud} 다.
 * order 는 이 엔드포인트의 허용 호출자로 {@code SERVICE_delivery-service} 하나만 선언한다.
 *
 * <p>직접 부르지 않는다 — 서킷 · 예외 변환은 {@code OrderShipmentInternalResponseSupport} 가 감싸고, application 에는 {@code OrderShipmentCommandPort} 로만 나간다.
 * 배송 완료 반영({@code delivery-completion})은 PR-B2 가 이 인터페이스에 더한다.
 */
@FeignClient(
    name = "${feign-client.target-services.order-service:order-service}",
    contextId = "orderShipmentClient",
    configuration = {InternalFeignClientConfiguration.class, OrderShipmentClientConfiguration.class})
public interface OrderShipmentClient {

    /** 주문 멱등이다 — 이미 출고된 뒤의 재요청은 바꾸지 않고 200. 거절은 4xx 봉투의 {@code resultCode}({@code ORDER_*})로 온다. */
    @PostMapping("/internal/v1/orders/{orderId}/shipment")
    Response<OrderFulfillmentInternalResponse> ship(@PathVariable("orderId") long orderId, @RequestBody OrderShipmentInternalRequest request);
}
