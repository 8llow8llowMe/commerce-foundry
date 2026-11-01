package com.commercefoundry.domainlayer.payment.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.payment.adapter.out.client.feign.dto.OrderPaymentSummaryInternalResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * order-service internal 결제용 주문 요약. 서비스 토큰을 싣는다({@link InternalFeignClientConfiguration}) — 사용자 {@code Authorization} 은 넘기지 않는다
 * ({@code security-guide.md} §9-5). {@code name} 은 대상의 Eureka 서비스 ID(order-service 의 {@code spring.application.name})이자 서비스 토큰의 {@code aud} 다.
 * order-service 는 이 엔드포인트의 허용 호출자로 {@code SERVICE_payment-service} 를 선언한다.
 *
 * <p>직접 부르지 않는다 — 서킷 · 예외 변환은 {@code OrderInternalResponseSupport} 가 감싸고, application 에는 {@code OrderPaymentSummaryPort} 로만 나간다.
 * 타임아웃은 {@code spring.cloud.openfeign.client.config.default}(연결 2초 · 응답 3초)다.
 */
@FeignClient(
    name = "${feign-client.target-services.order-service:order-service}",
    contextId = "orderPaymentSummaryClient",
    configuration = InternalFeignClientConfiguration.class)
public interface OrderPaymentSummaryClient {

    /** 주문이 없거나 그 회원의 주문이 아니면 404 {@code ORDER_001} 이다 — 존재를 숨긴다. */
    @GetMapping("/internal/v1/orders/{orderId}/payment-summary")
    Response<OrderPaymentSummaryInternalResponse> getPaymentSummary(@PathVariable("orderId") long orderId, @RequestParam("memberId") long memberId);
}
