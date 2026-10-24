package com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss;

import com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss.dto.TossConfirmRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * 토스페이먼츠 결제 API (외부). 직접 부르지 않는다 — {@link TossPaymentGatewayAdapter} 가 서킷 · 결과 분류({@link TossPaymentsResponseSupport})를 거쳐 부른다.
 *
 * <ul>
 *   <li><b>서비스 토큰 설정({@code InternalFeignClientConfiguration})을 붙이지 않는다</b> — 외부 API 다 ({@code coding-conventions.md} §10).
 *       이름이 Eureka 서비스 ID 가 아니라 kebab 논리명({@value #NAME})이고 {@code url} 로 바로 간다(로드밸런서를 거치지 않는다).</li>
 *   <li>인증 헤더(Basic)는 인터셉터가 아니라 메서드 인자로 받는다 — 인터셉터 · Capability 를 {@code @Bean} 으로 만들면 ArchUnit R5 가 막고
 *       ({@code security-guide.md} §9-11), 인자로 두면 이 클라이언트의 요청에만 실리는 것이 시그니처로 보인다.</li>
 *   <li>응답은 바이트로 받는다 — 본문 해석 실패를 Feign 디코딩 예외가 아니라 응답 계약 위반(UNKNOWN)으로 가르기 위해서다.
 *       2xx 가 아니면 Feign 기본 {@code ErrorDecoder} 가 {@code FeignException}(4xx {@code FeignClientException} · 5xx {@code FeignServerException})을 던진다.</li>
 *   <li>타임아웃 · 로그 수준은 {@code spring.cloud.openfeign.client.config.toss-payments}, 재시도 끄기는 {@link TossPaymentsClientConfiguration}.</li>
 * </ul>
 */
@FeignClient(name = TossPaymentsClient.NAME, url = "${toss.base-url}", configuration = TossPaymentsClientConfiguration.class)
public interface TossPaymentsClient {

    /** Feign 클라이언트 이름 — 타임아웃 설정 키 {@code spring.cloud.openfeign.client.config.toss-payments} 와 같다. */
    String NAME = "toss-payments";
    String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** 결제 승인. 결제 인증 뒤 10분 안에 불러야 한다 — 넘으면 404 {@code NOT_FOUND_PAYMENT_SESSION}. */
    @PostMapping(value = "/v1/payments/confirm", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    byte[] confirm(
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
        @RequestBody TossConfirmRequest request
    );

    /** 주문번호로 결제 조회. */
    @GetMapping(value = "/v1/payments/orders/{orderId}", produces = MediaType.APPLICATION_JSON_VALUE)
    byte[] getPaymentByOrderId(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @PathVariable("orderId") String orderId);

    /** 결제키로 결제 조회 — 어느 결제키가 승인되지 않았는지 확인할 때 쓴다. */
    @GetMapping(value = "/v1/payments/{paymentKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    byte[] getPaymentByPaymentKey(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @PathVariable("paymentKey") String paymentKey);
}
