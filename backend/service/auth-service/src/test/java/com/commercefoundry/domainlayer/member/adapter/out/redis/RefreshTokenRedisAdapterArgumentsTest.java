package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotation;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationPolicy;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationResult;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import com.commercefoundry.security.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Docker 없이 고정하는 부분 — 스크립트에 넘기는 KEYS · ARGV 의 개수 · 순서 · 모양, 결과 해석, Redis 오류 변환.
 * 스크립트 실행 자체는 {@link RefreshTokenRedisAdapterTest}(Testcontainers)가 본다.
 */
class RefreshTokenRedisAdapterArgumentsTest {

    private static final String FINGERPRINT = "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg";
    private static final String NEW_FINGERPRINT = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String FAMILY_ID = "3f2b8f2e-6a0e-4a52-9a43-2f7f8a3b1c11";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RefreshTokenRedisAdapter adapter = new RefreshTokenRedisAdapter(redisTemplate);

    @Test
    @DisplayName("로그인 저장 — KEYS [토큰, family], ARGV [memberId, familyId, TTL ms, 로그인 시각 ms]")
    void saveNewFamilyArguments() {
        Instant loginAt = Instant.parse("2026-10-18T00:00:00.123Z");

        adapter.saveNewFamily(FINGERPRINT, 42L, FAMILY_ID, loginAt, Duration.ofDays(14));

        verify(redisTemplate).execute(
            RefreshTokenRedisScripts.SAVE_NEW_FAMILY,
            List.of("auth:refresh-token:" + FINGERPRINT, "auth:refresh-family:" + FAMILY_ID),
            "42", FAMILY_ID, "1209600000", String.valueOf(loginAt.toEpochMilli())
        );
    }

    @Test
    @DisplayName("회전 — KEYS [옛 토큰, family, 새 토큰], ARGV [memberId, familyId, 지금 ms, 유예 ms, TTL ms, 형제 상한, 절대 수명 ms], 결과 {이름, TTL ms} 를 읽는다")
    void rotateArguments() {
        Instant now = Instant.parse("2026-10-18T00:00:00.123Z");
        RefreshTokenSession session = new RefreshTokenSession(FINGERPRINT, 42L, FAMILY_ID);
        List<String> keys = List.of("auth:refresh-token:" + FINGERPRINT, "auth:refresh-family:" + FAMILY_ID, "auth:refresh-token:" + NEW_FINGERPRINT);
        given(redisTemplate.execute(
            RefreshTokenRedisScripts.ROTATE, keys, "42", FAMILY_ID, String.valueOf(now.toEpochMilli()), "30000", "1209600000", "3", "2592000000"
        )).willReturn(List.of("REISSUED_IN_GRACE", 86_400_000L));

        RefreshTokenRotationPolicy policy = new RefreshTokenRotationPolicy(Duration.ofSeconds(30), 3, Duration.ofDays(14), Duration.ofDays(30));
        assertThat(adapter.rotate(session, NEW_FINGERPRINT, now, policy))
            .isEqualTo(new RefreshTokenRotationResult(RefreshTokenRotation.REISSUED_IN_GRACE, Duration.ofDays(1)));
    }

    @Test
    @DisplayName("회전 결과 해석 — 거절은 TTL 0, 모양이 다르면(이름만 · TTL 이 숫자 아님 · 없음) 스크립트와 코드가 어긋난 것이라 실패한다")
    void rotationResultShape() {
        assertThat(RefreshTokenRedisAdapter.toRotationResult(List.of("FAMILY_EXPIRED", 0L)))
            .isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_EXPIRED));
        assertThat(RefreshTokenRedisAdapter.toRotationResult(List.of("FAMILY_CORRUPTED", 0L)))
            .isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_CORRUPTED));
        assertThatThrownBy(() -> RefreshTokenRedisAdapter.toRotationResult(List.of("ROTATED"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RefreshTokenRedisAdapter.toRotationResult(List.of("ROTATED", "1000"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RefreshTokenRedisAdapter.toRotationResult(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RefreshTokenRedisAdapter.toRotationResult(List.of("ROTATED", 0L))).as("새 토큰을 저장한 판정은 TTL 이 있다")
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("스크립트의 반환 문자열은 RefreshTokenRotation 이름과 같고, 해시 필드 이름이 Java 상수와 같다")
    void scriptMatchesJava() {
        String rotate = RefreshTokenRedisScripts.ROTATE.getScriptAsString();
        for (RefreshTokenRotation rotation : RefreshTokenRotation.values()) {
            assertThat(rotate).contains("'" + rotation.name() + "'");
        }
        assertThat(rotate).contains("'" + RefreshTokenRedisScripts.MEMBER_ID + "'", "'" + RefreshTokenRedisScripts.FAMILY_ID + "'",
            "'" + RefreshTokenRedisScripts.ROTATED_AT + "'", "'" + RefreshTokenRedisScripts.SIBLINGS + "'", "ARGV[7]");
        assertThat(RefreshTokenRedisScripts.ROTATE.getResultType()).isEqualTo(List.class);
        assertThat(RefreshTokenRedisScripts.SAVE_NEW_FAMILY.getResultType()).isEqualTo(Long.class);
        assertThat(RefreshTokenRedisScripts.SAVE_NEW_FAMILY.getScriptAsString()).contains("ARGV[4]");
        assertThat(rotate).as("저장값은 정수 문자열만, 최소 남은 수명은 RefreshTokenRotationPolicy 와 같다")
            .contains("'^%d+$'", "remaining < " + RefreshTokenRotationPolicy.MIN_REMAINING_LIFETIME.toMillis());
        assertThat(rotate).as("저장된 시각은 유한하고 지금 + 허용 시계 오차 이하만 받는다 — 오차는 RefreshTokenRotationPolicy.CLOCK_SKEW 와 같다")
            .contains("millis == math.huge", "millis > latest", "local latest = now + " + RefreshTokenRotationPolicy.CLOCK_SKEW.toMillis() + "\n");
    }

    @Test
    @DisplayName("세션 읽기 — 해시의 memberId · familyId 를 읽고 family 키가 있어야 세션이다")
    @SuppressWarnings("unchecked")
    void findSession() {
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        given(redisTemplate.opsForHash()).willReturn(hash);
        given(hash.multiGet("auth:refresh-token:" + FINGERPRINT, List.of("memberId", "familyId"))).willReturn(List.of("42", FAMILY_ID));
        given(redisTemplate.hasKey("auth:refresh-family:" + FAMILY_ID)).willReturn(true, false);

        assertThat(adapter.findSession(FINGERPRINT)).contains(new RefreshTokenSession(FINGERPRINT, 42L, FAMILY_ID));
        assertThat(adapter.findSession(FINGERPRINT)).as("family 가 없으면 무효").isEmpty();
    }

    @Test
    @DisplayName("해시가 없거나 값이 규칙 밖이면 family 를 보지 않고 빈 값이다")
    @SuppressWarnings("unchecked")
    void findSessionOutOfRule() {
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        given(redisTemplate.opsForHash()).willReturn(hash);
        given(hash.multiGet(anyString(), any())).willReturn(
            Arrays.asList(null, null), List.of("not-a-number", FAMILY_ID), List.of("42", "bad:family")
        );

        assertThat(adapter.findSession(FINGERPRINT)).isEmpty();
        assertThat(adapter.findSession(FINGERPRINT)).isEmpty();
        assertThat(adapter.findSession(FINGERPRINT)).isEmpty();
        verify(redisTemplate, never()).hasKey(anyString());
    }

    @Test
    @DisplayName("family 폐기는 family 키 DEL 하나다")
    void revokeFamily() {
        adapter.revokeFamily(FAMILY_ID);

        verify(redisTemplate).delete("auth:refresh-family:" + FAMILY_ID);
    }

    @Test
    @DisplayName("Redis 오류(연결 실패 등)는 AUTH_013 이고 원인을 잇는다")
    void redisFailureBecomesUnavailable() {
        RedisConnectionFailureException failure = new RedisConnectionFailureException("connection refused");
        given(redisTemplate.execute(eq(RefreshTokenRedisScripts.SAVE_NEW_FAMILY), anyList(), any(Object[].class))).willThrow(failure);

        assertThatThrownBy(() -> adapter.saveNewFamily(FINGERPRINT, 42L, FAMILY_ID, Instant.EPOCH, Duration.ofDays(14)))
            .isInstanceOf(MemberException.class)
            .hasCause(failure)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
    }

    @Test
    @DisplayName("로그아웃 차단 — auth:revoked-access-token:{jti} 에 \"1\" 을 같은 명령의 TTL(상한 1시간 + clock-skew 60초 = 61분)로 쓴다")
    @SuppressWarnings("unchecked")
    void revokeAccessToken() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(values);
        String jti = UUID.randomUUID().toString();

        new AccessTokenRevocationRedisAdapter(redisTemplate, TestTokens.verification()).revoke(jti);

        verify(values).set("auth:revoked-access-token:" + jti, "1", Duration.ofMinutes(61));
    }
}
