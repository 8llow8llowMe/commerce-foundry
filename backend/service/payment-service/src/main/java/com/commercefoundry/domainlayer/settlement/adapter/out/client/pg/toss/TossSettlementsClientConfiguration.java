package com.commercefoundry.domainlayer.settlement.adapter.out.client.pg.toss;

import feign.Logger;
import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * {@link TossSettlementsClient} 에만 붙는 Feign 설정 — 클라이언트의 자식 컨텍스트에만 등록된다.
 *
 * <ul>
 *   <li><b>{@code @Configuration} 을 달지 않는다.</b> 스캔되면 서비스의 모든 Feign 클라이언트에 이 설정이 실린다.</li>
 *   <li>{@code RequestInterceptor} · {@code Capability} 를 여기서 만들지 않는다 ({@code security-guide.md} §9-11). 인증 헤더는 메서드 인자다.</li>
 * </ul>
 */
public class TossSettlementsClientConfiguration {

    /**
     * Feign 단계 재시도를 하지 않는다. 429 만 어댑터가 제한 백오프로 다시 부른다(GET 조회라 안전) — 5xx · 타임아웃 · 연결 실패는 다시 부르지 않고 수집 실패로 끝낸다.
     * 정기 실행(operations 작업 엔진)이 백오프로 다시 부르므로 여기서 겹쳐 재시도하면 토스 쪽 부하만 늘어난다.
     */
    @Bean
    public Retryer tossSettlementsRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /** 요청 · 응답을 로그에 남기지 않는다 — HEADERS 이상이면 시크릿 키가 든 Authorization 이, FULL 이면 결제키가 든 본문이 남는다 ({@code coding-conventions.md} §8 · §10). */
    @Bean
    public Logger.Level tossSettlementsLoggerLevel() {
        return Logger.Level.NONE;
    }
}
