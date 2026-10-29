package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptOutcome;
import com.commercefoundry.global.properties.AuthLoginGuardProperties;
import com.commercefoundry.support.TestLoginGuard;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Docker 없이 고정하는 부분 — 스크립트에 넘기는 KEYS · ARGV 의 모양, 결과 해석, Redis 오류 변환.
 * 스크립트 실행 자체는 {@link LoginAttemptRedisAdapterTest}(Testcontainers)가 본다.
 */
class LoginAttemptRedisAdapterArgumentsTest {

    private static final String EMAIL = "member@example.com";
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final LoginAttemptKeyHasher hasher = new LoginAttemptKeyHasher(TestLoginGuard.properties());
    private final LoginAttemptRedisAdapter adapter = new LoginAttemptRedisAdapter(redisTemplate, hasher, new TokenStoreRedisSupport(Clock.systemUTC()));

    @Test
    @DisplayName("기록 — KEYS [auth:login-attempt:{HMAC}], ARGV [창 ms], 결과 {시도 수, 남은 ms} 를 읽는다")
    void recordArguments() {
        given(redisTemplate.execute(LoginAttemptRedisScripts.RECORD, List.of("auth:login-attempt:" + hasher.hash(EMAIL)), "900000"))
            .willReturn(List.of(3L, 600_000L));

        assertThat(adapter.record(AttemptKind.LOGIN, EMAIL, WINDOW)).isEqualTo(new LoginAttemptOutcome(3, Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("회원가입 기록은 auth:sign-up-attempt:{HMAC} 키를 쓴다")
    void signUpRecordUsesSignUpKey() {
        given(redisTemplate.execute(LoginAttemptRedisScripts.RECORD, List.of("auth:sign-up-attempt:" + hasher.hash(EMAIL)), "600000"))
            .willReturn(List.of(1L, 600_000L));

        assertThat(adapter.record(AttemptKind.SIGN_UP, EMAIL, Duration.ofMinutes(10))).isEqualTo(new LoginAttemptOutcome(1, Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("관리자 로그인 기록은 auth:admin-login-attempt:{HMAC} 키를 쓴다 — 고객 로그인 키와 다르다")
    void adminLoginRecordUsesAdminKey() {
        given(redisTemplate.execute(LoginAttemptRedisScripts.RECORD, List.of("auth:admin-login-attempt:" + hasher.hash(EMAIL)), "900000"))
            .willReturn(List.of(1L, 900_000L));

        assertThat(adapter.record(AttemptKind.ADMIN_LOGIN, EMAIL, WINDOW)).isEqualTo(new LoginAttemptOutcome(1, WINDOW));
        adapter.reset(AttemptKind.ADMIN_LOGIN, EMAIL);
        verify(redisTemplate).delete("auth:admin-login-attempt:" + hasher.hash(EMAIL));
    }

    @Test
    @DisplayName("로그 꼬리표는 키 HMAC 의 앞 8자이고 변형 이메일도 같다 — 전체 해시 · 이메일 원문이 아니고 Redis 를 부르지 않는다")
    void subjectTagIsHashPrefix() {
        String tag = adapter.subjectTag(EMAIL);

        assertThat(tag).hasSize(8).isEqualTo(hasher.hash(EMAIL).substring(0, 8)).doesNotContain("@", "member");
        assertThat(adapter.subjectTag(" Member@Example.COM ")).isEqualTo(tag);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("reset 은 그 종류 키 DEL 하나다")
    void resetDeletesKey() {
        adapter.reset(AttemptKind.LOGIN, EMAIL);

        verify(redisTemplate).delete("auth:login-attempt:" + hasher.hash(EMAIL));
    }

    @Test
    @DisplayName("대소문자 · 앞뒤 공백만 다른 이메일은 Member.canonicalEmail 을 거쳐 같은 키로 간다 — 부르는 쪽이 정규화를 빠뜨려도 새 창을 열지 못한다")
    void emailVariantsUseSameKey() {
        List<String> sameKey = List.of("auth:login-attempt:" + hasher.hash(EMAIL));
        // 다른 키로 가면 스텁이 없어 결과 모양 오류(IllegalStateException)로 실패한다
        given(redisTemplate.execute(LoginAttemptRedisScripts.RECORD, sameKey, "900000"))
            .willReturn(List.of(1L, 900_000L))
            .willReturn(List.of(2L, 800_000L))
            .willReturn(List.of(3L, 700_000L));

        assertThat(adapter.record(AttemptKind.LOGIN, "Member@Example.com ", WINDOW).count()).isEqualTo(1);
        assertThat(adapter.record(AttemptKind.LOGIN, " MEMBER@example.com", WINDOW).count()).isEqualTo(2);
        assertThat(adapter.record(AttemptKind.LOGIN, EMAIL, WINDOW).count()).isEqualTo(3);
        verify(redisTemplate, times(3)).execute(LoginAttemptRedisScripts.RECORD, sameKey, "900000");

        adapter.reset(AttemptKind.LOGIN, " Member@EXAMPLE.com ");
        verify(redisTemplate).delete(sameKey.get(0));
        assertThat(hasher.hash("Member@Example.com ")).as("해셔는 받은 값을 그대로 해시한다 — 정규화는 어댑터 한 곳에서 한다").isNotEqualTo(hasher.hash(EMAIL));
    }

    @Test
    @DisplayName("결과 해석 — 모양이 다르면(하나뿐 · 숫자 아님 · 없음) 스크립트와 코드가 어긋난 것이라 실패한다")
    void outcomeShape() {
        assertThat(LoginAttemptRedisAdapter.toOutcome(List.of(1L, 0L))).isEqualTo(new LoginAttemptOutcome(1, Duration.ZERO));
        assertThatThrownBy(() -> LoginAttemptRedisAdapter.toOutcome(List.of(1L))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LoginAttemptRedisAdapter.toOutcome(List.of(1L, "1000"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LoginAttemptRedisAdapter.toOutcome(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LoginAttemptRedisAdapter.toOutcome(List.of(0L, 1000L))).as("시도 수는 1 이상이다").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("창이 1ms 미만이면 Redis 를 부르지 않고 거절한다 — PEXPIRE 가 키를 바로 지워 창이 서지 않는다")
    void subMillisecondWindowRejected() {
        assertThatThrownBy(() -> adapter.record(AttemptKind.LOGIN, EMAIL, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.record(AttemptKind.LOGIN, EMAIL, Duration.ofNanos(999_999))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("Redis 오류(연결 실패 등)는 AUTH_013 이고 원인을 잇는다 — 기록 · reset 모두")
    void redisFailureBecomesUnavailable() {
        RedisConnectionFailureException failure = new RedisConnectionFailureException("connection refused");
        given(redisTemplate.execute(eq(LoginAttemptRedisScripts.RECORD), anyList(), any(Object[].class))).willThrow(failure);
        given(redisTemplate.delete(anyString())).willThrow(failure);

        assertThatThrownBy(() -> adapter.record(AttemptKind.LOGIN, EMAIL, WINDOW))
            .isInstanceOf(MemberException.class)
            .hasCause(failure)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        assertThatThrownBy(() -> adapter.reset(AttemptKind.LOGIN, EMAIL))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
    }

    @Test
    @DisplayName("꺼진 해셔로 부르면 Redis 에 닿기 전에 IllegalStateException 이다 — 꺼진 구성에서는 부르지 않는다")
    void disabledHasherFailsBeforeRedis() {
        LoginAttemptKeyHasher disabled = new LoginAttemptKeyHasher(new AuthLoginGuardProperties(false, 5, WINDOW, null, null));
        LoginAttemptRedisAdapter disabledAdapter = new LoginAttemptRedisAdapter(redisTemplate, disabled, new TokenStoreRedisSupport(Clock.systemUTC()));

        assertThatThrownBy(() -> disabledAdapter.record(AttemptKind.LOGIN, EMAIL, WINDOW)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("스크립트는 첫 시도에만 창을 걸고 {시도 수, PTTL} 을 돌려준다")
    void scriptShape() {
        String script = LoginAttemptRedisScripts.RECORD.getScriptAsString();

        assertThat(script).contains("'INCR', KEYS[1]", "if count == 1 then", "'PTTL', KEYS[1]", "return {count, ttl}", "ARGV[1]");
        assertThat(LoginAttemptRedisScripts.RECORD.getResultType()).isEqualTo(List.class);
    }

    @Test
    @DisplayName("가입 IP 키는 게이트웨이 HMAC 식별자를 그대로 쓰며 이메일 정규화와 해시를 적용하지 않는다")
    void signUpIpPreservesGatewaySubject() {
        String subject = "AbCdEfGhIjKlMnOpQrStUw";
        List<String> keys = List.of("auth:sign-up-ip-attempt:" + subject);
        given(redisTemplate.execute(LoginAttemptRedisScripts.RECORD, keys, "900000"))
            .willReturn(List.of(1L, 900_000L));
        assertThat(adapter.record(AttemptKind.SIGN_UP_IP, subject, WINDOW)).isEqualTo(new LoginAttemptOutcome(1, WINDOW));
        adapter.reset(AttemptKind.SIGN_UP_IP, subject);
        verify(redisTemplate).delete(keys.getFirst());
    }

    @Test
    @DisplayName("관리자 OTP 키는 auth:admin-otp-attempt:{memberId} 이고 해시 키가 없어도(시도 제한 꺼짐) 만든다. 숫자가 아닌 주체는 Redis 전에 거절한다")
    void adminOtpUsesMemberIdWithoutHashKey() {
        LoginAttemptKeyHasher disabled = new LoginAttemptKeyHasher(new AuthLoginGuardProperties(false, 5, WINDOW, null, null));
        LoginAttemptRedisAdapter disabledAdapter = new LoginAttemptRedisAdapter(redisTemplate, disabled, new TokenStoreRedisSupport(Clock.systemUTC()));
        List<String> keys = List.of("auth:admin-otp-attempt:4611686018427387904");
        given(redisTemplate.execute(LoginAttemptRedisScripts.RECORD, keys, "900000")).willReturn(List.of(2L, 800_000L));

        assertThat(disabledAdapter.record(AttemptKind.ADMIN_OTP, "4611686018427387904", WINDOW)).isEqualTo(new LoginAttemptOutcome(2, Duration.ofMillis(800_000)));
        disabledAdapter.reset(AttemptKind.ADMIN_OTP, "4611686018427387904");
        verify(redisTemplate).delete(keys.getFirst());

        assertThatThrownBy(() -> disabledAdapter.record(AttemptKind.ADMIN_OTP, EMAIL, WINDOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> disabledAdapter.reset(AttemptKind.ADMIN_OTP, "0")).isInstanceOf(IllegalArgumentException.class);
    }

}
