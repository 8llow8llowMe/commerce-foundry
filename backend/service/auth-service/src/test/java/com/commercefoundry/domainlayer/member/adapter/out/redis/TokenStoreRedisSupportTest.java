package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.support.TestLoginGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Redis 장애 warn 은 간격(1분)마다 한 건, 나머지는 debug 다 ({@code security-guide.md} §2-3 · §8, #234). 세 어댑터가 한 인스턴스로 간격을 공유한다.
 * 로그는 예외 타입만 남긴다 — 메시지(키 · 이메일 HMAC · 지문이 섞일 수 있다)는 어떤 레벨에도 나오지 않는다.
 * debug 줄을 세야 하므로 이 클래스의 로거만 DEBUG 로 열고 끝나면 되돌린다.
 */
@ExtendWith(OutputCaptureExtension.class)
class TokenStoreRedisSupportTest {

    private static final String LOG_TEXT = "token store unavailable: ";
    private static final Pattern WARN = Pattern.compile("\\bWARN\\b");
    private static final Pattern DEBUG = Pattern.compile("\\bDEBUG\\b");
    private static final String SECRET_MESSAGE = "connection refused for auth:login-attempt:secret-hmac member@example.com";

    private final Logger supportLogger = (Logger) LoggerFactory.getLogger(TokenStoreRedisSupport.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-24T00:00:00Z"));
    private final TokenStoreRedisSupport support = new TokenStoreRedisSupport(clock);
    private Level previousLevel;

    @BeforeEach
    void openDebug() {
        previousLevel = supportLogger.getLevel();
        supportLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void restoreLevel() {
        supportLogger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("간격 안에서 N회 실패하면 warn 1건(nextWarnIn 포함) · debug N-1건이다")
    void warnOncePerInterval(CapturedOutput output) {
        failTimes(5);

        assertThat(lines(output, WARN)).hasSize(1).first().asString()
            .contains(LOG_TEXT + RedisConnectionFailureException.class.getName())
            .contains("nextWarnIn=" + TokenStoreRedisSupport.UNAVAILABLE_WARN_INTERVAL);
        assertThat(lines(output, DEBUG)).hasSize(4).allSatisfy(line -> assertThat(line).doesNotContain("nextWarnIn"));
    }

    @Test
    @DisplayName("간격이 지나면 다시 warn 1건이다 — 간격 직전까지는 debug 다")
    void warnAgainAfterInterval(CapturedOutput output) {
        failTimes(1);
        clock.advance(TokenStoreRedisSupport.UNAVAILABLE_WARN_INTERVAL.minusMillis(1));
        failTimes(1);
        assertThat(lines(output, WARN)).hasSize(1);

        clock.advance(Duration.ofMillis(1));
        failTimes(2);

        assertThat(lines(output, WARN)).hasSize(2);
        assertThat(lines(output, DEBUG)).hasSize(2);
    }

    @Test
    @DisplayName("여러 스레드가 동시에 실패해도 간격당 warn 은 1건이다 — CAS 로 자리를 얻은 요청 하나만")
    void concurrentFailuresWarnOnce(CapturedOutput output) throws Exception {
        int threads = 8;
        int perThread = 25;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    failTimes(perThread);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(lines(output, WARN)).hasSize(1);
        assertThat(lines(output, DEBUG)).hasSize(threads * perThread - 1);
    }

    @Test
    @DisplayName("로그에는 예외 타입만 남는다 — 예외 메시지(키 · 이메일)는 warn · debug 어디에도 없다")
    void logsExceptionTypeOnly(CapturedOutput output) {
        failTimes(2);
        assertThatThrownBy(() -> support.execute(() -> {
            throw new QueryTimeoutException(SECRET_MESSAGE);
        })).isInstanceOf(MemberException.class);

        assertThat(lines(output, DEBUG)).anySatisfy(line -> assertThat(line).contains(QueryTimeoutException.class.getName()));
        assertThat(output.getAll()).doesNotContain(SECRET_MESSAGE).doesNotContain("secret-hmac").doesNotContain("member@example.com");
    }

    @Test
    @DisplayName("warn · debug 어느 쪽이든 AUTH_013(503)으로 바꾸고 원인을 잇는다 — 성공하면 값을 그대로 돌려준다")
    void translatesToUnavailableWithCause() {
        RedisConnectionFailureException failure = new RedisConnectionFailureException(SECRET_MESSAGE);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> support.execute(() -> {
                throw failure;
            }))
                .isInstanceOf(MemberException.class)
                .hasCause(failure)
                .extracting(exception -> ((MemberException) exception).getErrorCode())
                .isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        }
        assertThat(support.execute(() -> "ok")).isEqualTo("ok");
    }

    @Test
    @DisplayName("Redis 가 아닌 예외는 바꾸지 않고 로그도 남기지 않는다")
    void nonRedisExceptionPassesThrough(CapturedOutput output) {
        IllegalStateException shape = new IllegalStateException("결과 모양이 다릅니다");

        assertThatThrownBy(() -> support.execute(() -> {
            throw shape;
        })).isSameAs(shape);
        assertThat(output.getAll()).doesNotContain(LOG_TEXT);
    }

    @Test
    @DisplayName("완료 기준 — 실패하는 Redis 템플릿으로 시도 계수 어댑터를 N회 불러도 warn 은 간격당 1건이고, 세 어댑터가 간격을 공유한다")
    @SuppressWarnings("unchecked")
    void adaptersShareInterval(CapturedOutput output) {
        StringRedisTemplate failingTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        RedisConnectionFailureException failure = new RedisConnectionFailureException(SECRET_MESSAGE);
        given(failingTemplate.execute(eq(LoginAttemptRedisScripts.RECORD), anyList(), any(Object[].class))).willThrow(failure);
        given(failingTemplate.delete(anyString())).willThrow(failure);
        given(failingTemplate.opsForValue()).willReturn(values);
        willThrow(failure).given(values).set(anyString(), eq(AccessTokenRevocationRedisAdapter.REVOKED), any(Duration.class));

        LoginAttemptRedisAdapter loginAttempts = new LoginAttemptRedisAdapter(failingTemplate, new LoginAttemptKeyHasher(TestLoginGuard.properties()), support);
        RefreshTokenRedisAdapter refreshTokens = new RefreshTokenRedisAdapter(failingTemplate, support);
        AccessTokenRevocationRedisAdapter revocations = new AccessTokenRevocationRedisAdapter(failingTemplate, TestTokens.verification(), support);

        for (int i = 0; i < 10; i++) {
            assertUnavailable(() -> loginAttempts.record(AttemptKind.LOGIN, "member@example.com", Duration.ofMinutes(15)));
        }
        assertUnavailable(() -> refreshTokens.revokeFamily(UUID.randomUUID().toString()));
        assertUnavailable(() -> revocations.revoke(UUID.randomUUID().toString()));

        assertThat(lines(output, WARN)).hasSize(1);
        assertThat(lines(output, DEBUG)).hasSize(11);

        clock.advance(TokenStoreRedisSupport.UNAVAILABLE_WARN_INTERVAL);
        for (int i = 0; i < 10; i++) {
            assertUnavailable(() -> loginAttempts.record(AttemptKind.LOGIN, "member@example.com", Duration.ofMinutes(15)));
        }

        assertThat(lines(output, WARN)).hasSize(2);
        assertThat(lines(output, DEBUG)).hasSize(20);
        assertThat(output.getAll()).doesNotContain(SECRET_MESSAGE);
    }

    private void failTimes(int times) {
        for (int i = 0; i < times; i++) {
            assertThatThrownBy(() -> support.execute(() -> {
                throw new RedisConnectionFailureException(SECRET_MESSAGE);
            })).isInstanceOf(MemberException.class);
        }
    }

    private static void assertUnavailable(Runnable call) {
        assertThatThrownBy(call::run)
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
    }

    private static List<String> lines(CapturedOutput output, Pattern level) {
        return output.getAll().lines()
            .filter(line -> line.contains(LOG_TEXT))
            .filter(line -> level.matcher(line).find())
            .toList();
    }

    /** 테스트가 직접 움직이는 고정 시계 — 간격 경계를 밀리초 단위로 본다. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("UTC 하나만 쓴다");
        }
    }
}
