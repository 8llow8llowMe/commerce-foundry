package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpChallenge;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Docker 없이 고정하는 부분 — 키 · 값 모양 · SET NX PX · GETDEL · 손상된 값 · Redis 오류 변환 (#238 PR-B). 실제 TTL · 원자성은
 * {@link AdminOtpChallengeRedisAdapterTest}(Testcontainers)가 본다. 값은 전부 테스트 전용이다.
 */
class AdminOtpChallengeRedisAdapterArgumentsTest {

    private static final String HASH = "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg";
    private static final String KEY = "auth:admin-otp-challenge:" + HASH;
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final AdminOtpChallenge CHALLENGE = new AdminOtpChallenge(4611686018427387904L, Instant.ofEpochMilli(1793242800000L));
    private static final String VALUE = "4611686018427387904:1793242800000";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final AdminOtpChallengeRedisAdapter adapter = new AdminOtpChallengeRedisAdapter(redisTemplate, new TokenStoreRedisSupport(Clock.systemUTC()));

    {
        given(redisTemplate.opsForValue()).willReturn(values);
    }

    @Test
    @DisplayName("저장은 auth:admin-otp-challenge:{해시} 에 memberId 를 SET NX PX 로, 조회는 GET, 소비는 GETDEL 이다")
    void commandShapes() {
        given(values.setIfAbsent(KEY, VALUE, TTL)).willReturn(true);
        given(values.get(KEY)).willReturn(VALUE);
        given(values.getAndDelete(KEY)).willReturn(VALUE, (String) null);

        assertThat(adapter.save(HASH, CHALLENGE, TTL)).isTrue();
        assertThat(adapter.find(HASH)).isEqualTo(Optional.of(CHALLENGE));
        assertThat(adapter.consume(HASH)).isEqualTo(Optional.of(CHALLENGE));
        assertThat(adapter.consume(HASH)).isEmpty();
        verify(values).setIfAbsent(KEY, VALUE, TTL);
    }

    @Test
    @DisplayName("해시가 겹치면(NX 실패) false, 숫자가 아닌 값 · 넘치는 값은 없는 챌린지로 본다")
    void collisionAndCorruptValues() {
        given(values.setIfAbsent(KEY, "9:1793242800000", TTL)).willReturn(false);
        given(values.get(KEY)).willReturn("abc", "9", "0:1793242800000", "-1:1793242800000", "9:99999999999999999999", null);

        assertThat(adapter.save(HASH, new AdminOtpChallenge(9L, CHALLENGE.issuedAt()), TTL)).isFalse();
        for (int i = 0; i < 6; i++) {
            assertThat(adapter.find(HASH)).isEmpty();
        }
    }

    @Test
    @DisplayName("1ms 미만 TTL 은 거절한다")
    void rejectsZeroTtl() {
        assertThatThrownBy(() -> adapter.save(HASH, CHALLENGE, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Redis 오류는 AUTH_013 이고 키(해시)를 메시지에 싣지 않는다 — 저장 · 조회 · 소비 모두")
    void redisFailure() {
        RedisConnectionFailureException down = new RedisConnectionFailureException("down");
        willThrow(down).given(values).setIfAbsent(anyString(), anyString(), any(Duration.class));
        willThrow(down).given(values).get(anyString());
        willThrow(down).given(values).getAndDelete(anyString());

        for (Throwable thrown : new Throwable[] {
            catchThrowable(() -> adapter.save(HASH, CHALLENGE, TTL)), catchThrowable(() -> adapter.find(HASH)), catchThrowable(() -> adapter.consume(HASH))}) {
            assertThat(thrown).isInstanceOf(MemberException.class);
            assertThat(((MemberException) thrown).getErrorCode()).isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
            assertThat(thrown.getMessage()).doesNotContain(HASH);
        }
    }
}
