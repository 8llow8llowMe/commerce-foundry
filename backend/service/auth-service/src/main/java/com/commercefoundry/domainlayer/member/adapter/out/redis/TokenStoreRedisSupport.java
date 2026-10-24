package com.commercefoundry.domainlayer.member.adapter.out.redis;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 토큰 저장소 어댑터가 같이 쓰는 Redis 오류 변환. 연결 실패 · 명령 타임아웃 · 스크립트 오류(WRONGTYPE 등)는 모두 {@link DataAccessException} 이고
 * 여기서 {@code AUTH_013}(503)으로 바꾼다. 로그에는 예외 타입만 남긴다 — 메시지에 키(지문 · jti · 이메일 HMAC)가 섞일 수 있다.
 * <p>
 * 로그인 · 가입 · 재발급 · 로그아웃이 모두 Redis 를 부르므로 실패마다 warn 을 남기면 장애 한 번이 요청 수만큼의 줄이 된다.
 * 그래서 warn 은 {@link #UNAVAILABLE_WARN_INTERVAL} 마다 한 번(CAS 로 자리를 얻은 요청 하나)만 남기고 나머지는 debug 로 남긴다.
 * 세 어댑터(refresh · 차단 목록 · 시도 계수)는 이 빈 하나를 주입받아 간격을 공유한다 — 같은 Redis 의 공통 장애라 어댑터별로 따로 세지 않는다.
 */
@Slf4j
@Component
final class TokenStoreRedisSupport {

    /** warn 간격. 운영에서 바꿀 일이 없어 설정값으로 두지 않는다 — 게이트웨이 fail-open warn 과 같은 1분이다. */
    static final Duration UNAVAILABLE_WARN_INTERVAL = Duration.ofMinutes(1);

    private final Clock clock;
    private final AtomicLong nextWarnAtMillis = new AtomicLong(Long.MIN_VALUE);

    /** 시계는 {@code AuthServiceBeansConfig#clock()} 빈이다 — refresh 회전 판정과 같은 시계라 테스트는 고정 시계로 바꿔 끼운다. */
    TokenStoreRedisSupport(Clock clock) {
        this.clock = clock;
    }

    <T> T execute(Supplier<T> command) {
        try {
            return command.get();
        } catch (DataAccessException exception) {
            logUnavailable(exception);
            throw new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE, exception);
        }
    }

    private void logUnavailable(DataAccessException exception) {
        String reason = exception.getClass().getName();
        if (claimWarnSlot()) {
            log.warn("token store unavailable: {}, nextWarnIn={}", reason, UNAVAILABLE_WARN_INTERVAL);
        } else {
            log.debug("token store unavailable: {}", reason);
        }
    }

    /** 간격이 지났고 CAS 로 다음 시각을 먼저 옮긴 요청만 참이다 — 동시에 실패해도 간격당 한 요청만 warn 한다. */
    private boolean claimWarnSlot() {
        long now = clock.millis();
        long next = nextWarnAtMillis.get();
        return now >= next && nextWarnAtMillis.compareAndSet(next, now + UNAVAILABLE_WARN_INTERVAL.toMillis());
    }
}
