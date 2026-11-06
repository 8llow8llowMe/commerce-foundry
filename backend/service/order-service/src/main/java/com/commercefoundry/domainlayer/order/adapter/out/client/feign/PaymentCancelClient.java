package com.commercefoundry.domainlayer.order.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.PaymentCancelInternalRequest;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.PaymentCancelInternalResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * payment-service internal 결제 취소 (#16 PR-2, {@code payment-guide.md} §8). 서비스 토큰을 싣는다({@link InternalFeignClientConfiguration}, {@code aud=payment-service}).
 * 결제 조회({@link PaymentClient})와 대상이 같아 {@code contextId} 로 나눈다 — PG 취소를 기다리므로 read timeout 을
 * {@code spring.cloud.openfeign.client.config.paymentCancelClient} 로 따로 길게 둔다.
 *
 * <p>직접 부르지 않는다 — 서킷 · 결과 분류는 {@code PaymentCancelInternalResponseSupport} 가 감싸고, application 에는 {@code PaymentCancelPort} 로만 나간다.
 */
@FeignClient(
    name = "${feign-client.target-services.payment-service:payment-service}",
    contextId = "paymentCancelClient",
    configuration = InternalFeignClientConfiguration.class)
public interface PaymentCancelClient {

    /** 같은 클레임의 성공 취소가 있으면 PG 호출 없이 200 이다. 거절은 4xx 봉투의 {@code resultCode}({@code PAYMENT_*}), 미확정은 503 {@code PAYMENT_018}. */
    @PostMapping("/internal/v1/payments/{paymentId}/cancellations")
    Response<PaymentCancelInternalResponse> cancel(
        @PathVariable("paymentId") long paymentId, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody PaymentCancelInternalRequest request);
}
