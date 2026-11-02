package com.commercefoundry.domainlayer.order.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.PaymentInternalResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * payment-service internal 결제 조회 (#308, {@code payment-guide.md} §6). 서비스 토큰을 싣는다({@link InternalFeignClientConfiguration}) — {@code aud} 는 대상 이름
 * {@code payment-service} 이고, payment 는 허용 호출자로 {@code SERVICE_order-service} 하나를 선언한다 ({@code security-guide.md} §9-4 · §9-5).
 * 같은 대상에 다른 클라이언트가 생겨도 이름이 겹치지 않게 {@code contextId} 를 둔다. 타임아웃은 {@code spring.cloud.openfeign.client.config.default} 다.
 *
 * <p>직접 부르지 않는다 — 서킷 · 예외 변환은 {@code PaymentInternalResponseSupport} 가 감싸고, application 에는 {@code PaymentQueryPort} 로만 나간다.
 */
@FeignClient(
    name = "${feign-client.target-services.payment-service:payment-service}",
    contextId = "paymentClient",
    configuration = InternalFeignClientConfiguration.class)
public interface PaymentClient {

    /** 없으면 404 {@code PAYMENT_001}. */
    @GetMapping("/internal/v1/payments/{paymentId}")
    Response<PaymentInternalResponse> getPayment(@PathVariable("paymentId") long paymentId);
}
