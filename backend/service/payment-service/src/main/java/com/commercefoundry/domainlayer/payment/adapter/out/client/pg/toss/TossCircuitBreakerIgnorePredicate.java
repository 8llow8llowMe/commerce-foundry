package com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss;

import feign.FeignException;
import java.util.function.Predicate;

/**
 * 서킷 {@code toss} 가 실패로 세지 않을 예외 — {@code application.yml} 의 {@code resilience4j.circuitbreaker.instances.toss.ignore-exception-predicate}.
 *
 * <p>4xx({@code FeignClientException})는 우리 요청 · 설정 문제이거나 업무 거절(카드 거절 등)이라 세지 않는다. 429({@code TooManyRequests})만은 센다 —
 * 토스가 요청을 감당하지 못한다는 신호라 5xx 와 같이 다룬다. Retry-After 가 붙은 429 는 Feign 이 {@code RetryableException} 으로 내므로 원래 세어진다 —
 * 이 술어로 Retry-After 유무와 관계없이 같아진다.
 *
 * <p>Resilience4j 가 이름으로 만들므로 인자 없는 public 생성자가 있어야 한다. 빈이 아니다.
 */
public class TossCircuitBreakerIgnorePredicate implements Predicate<Throwable> {

    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    @Override
    public boolean test(Throwable throwable) {
        return throwable instanceof FeignException.FeignClientException clientException && clientException.status() != HTTP_TOO_MANY_REQUESTS;
    }
}
