package com.commercefoundry.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 흐름 테스트가 같이 쓰는 저장소 스텁 — refresh 토큰 저장소 · 시도 계수기(Redis 포트)를 인메모리로, 시계를 {@link AdjustableClock} 으로 바꾼다({@code @Primary}).
 * {@code @SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc @Import(InMemoryAuthStoresConfig.class)} 가 같은 테스트끼리 컨텍스트 하나를 같이 쓴다.
 * 쓰는 테스트는 {@code @AfterEach} 에서 스텁 상태(장애 스위치 · 시계 · 시도 창)를 되돌린다 — 다른 테스트에 번지지 않게.
 * 실제 Lua · 키 · TTL 은 Testcontainers 테스트({@code RefreshTokenRedisAdapterTest} · {@code LoginAttemptRedisAdapterTest})가 본다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class InMemoryAuthStoresConfig {

    @Bean
    @Primary
    InMemoryRefreshTokenStore inMemoryRefreshTokenStore() {
        return new InMemoryRefreshTokenStore();
    }

    /** 회전 판정 · 시도 창 · 장애 warn 간격의 시계(Processor 의 {@code Clock}). access 토큰 발급 · 검증은 각자 시스템 시계라 앞당겨도 영향이 없다. */
    @Bean
    @Primary
    AdjustableClock adjustableClock() {
        return new AdjustableClock();
    }

    @Bean
    @Primary
    InMemoryLoginAttemptStore inMemoryLoginAttemptStore(AdjustableClock adjustableClock) {
        return new InMemoryLoginAttemptStore(adjustableClock);
    }
}
