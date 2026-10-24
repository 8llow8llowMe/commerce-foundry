package com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss;

import feign.Logger;
import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * {@link TossPaymentsClient} 에만 붙는 Feign 설정 — 클라이언트의 자식 컨텍스트에만 등록된다.
 *
 * <ul>
 *   <li><b>{@code @Configuration} 을 달지 않는다.</b> 스캔되면 서비스의 모든 Feign 클라이언트(앞으로 붙을 internal 클라이언트 포함)에 이 설정이 실린다.</li>
 *   <li>{@code RequestInterceptor} · {@code Capability} 를 여기서 만들지 않는다 — ArchUnit R5 는 클라이언트 설정 안의 {@code @Bean} 도 막는다
 *       ({@code security-guide.md} §9-11). 인증 헤더는 {@link TossPaymentsClient} 의 메서드 인자다.</li>
 * </ul>
 */
public class TossPaymentsClientConfiguration {

    /**
     * 자동 재시도를 하지 않는다. 승인은 멱등 키가 있어도 같은 요청을 다시 보내면 409 {@code IDEMPOTENT_REQUEST_PROCESSING} · 400 {@code ALREADY_PROCESSED_PAYMENT}
     * 같은 응답이 섞여 첫 요청의 결과를 해석할 수 없게 된다. 다시 보낼지는 Processor 가 결제 시도 상태로 정한다.
     * Spring Cloud OpenFeign 기본값도 재시도 없음이지만, 기본값이 바뀌거나 전역 {@code Retryer} 빈이 생겨도 이 클라이언트는 따르지 않게 명시한다.
     *
     * <p>HTTP 클라이언트 단계의 POST 재전송이 없다는 것은 기본 {@code feign.Client.Default}(JDK {@code HttpURLConnection}, Content-Length 가 있으면
     * 고정 길이 스트리밍 — 요청 본문을 버퍼링하지 않아 JDK 가 다시 보내지 못한다)를 전제로 한다. feign-hc5 · okhttp 같은 다른 클라이언트를 붙이면
     * 그 클라이언트의 재시도 전략과 연결 단계 예외 타입(연결 실패 = NotSent 판정)을 다시 확인한다.
     */
    @Bean
    public Retryer tossPaymentsRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /** 요청 · 응답을 로그에 남기지 않는다 — HEADERS 이상이면 시크릿 키가 든 Authorization 이, FULL 이면 결제키가 든 본문이 남는다 ({@code coding-conventions.md} §8 · §10). */
    @Bean
    public Logger.Level tossPaymentsLoggerLevel() {
        return Logger.Level.NONE;
    }
}
