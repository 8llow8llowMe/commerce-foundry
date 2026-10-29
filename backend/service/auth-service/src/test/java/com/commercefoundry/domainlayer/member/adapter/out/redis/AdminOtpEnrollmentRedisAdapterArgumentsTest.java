package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipher;
import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipherTest;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Docker 없이 고정하는 부분 — 키 · 값 모양 · TTL · 복호화 실패 · Redis 오류 변환. 실제 TTL 동작은 {@link AdminOtpEnrollmentRedisAdapterTest}(Testcontainers)가 본다.
 * 값은 전부 테스트 전용이다.
 */
class AdminOtpEnrollmentRedisAdapterArgumentsTest {

    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final Duration TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final AdminOtpSecretCipher cipher = AdminOtpSecretCipherTest.cipher(AdminOtpSecretCipherTest.KEY, null);
    private final AdminOtpEnrollmentRedisAdapter adapter = new AdminOtpEnrollmentRedisAdapter(redisTemplate, cipher, new TokenStoreRedisSupport(Clock.systemUTC()));

    {
        given(redisTemplate.opsForValue()).willReturn(values);
    }

    @Test
    @DisplayName("저장 — auth:admin-otp-enrollment:{memberId} 에 {keyId}:{암호문} 을 TTL 과 함께 SET 한다. 원문은 값에 없다")
    void saveShape() {
        adapter.save(42L, SECRET, TTL);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("auth:admin-otp-enrollment:42"), value.capture(), eq(TTL));
        assertThat(value.getValue()).startsWith(cipher.currentKeyId() + ":").doesNotContain("1234567890");

        given(values.get("auth:admin-otp-enrollment:42")).willReturn(value.getValue());
        assertThat(adapter.find(42L)).hasValueSatisfying(secret -> assertThat(secret).isEqualTo(SECRET));
    }

    @Test
    @DisplayName("다른 회원의 암호문 · 모양이 깨진 값 · 모르는 키는 AUTH_039 이고, 없으면 비어 있다")
    void rejectsForeignOrMalformed() {
        AdminOtpSecretCipher.Sealed other = cipher.sealEnrollment(43L, SECRET);
        given(values.get("auth:admin-otp-enrollment:42")).willReturn(other.keyId() + ":" + other.ciphertext(), "garbage", ":abc", "unknownKey:" + other.ciphertext());

        for (int i = 0; i < 4; i++) {
            Throwable thrown = catchThrowable(() -> adapter.find(42L));
            assertThat(thrown).isInstanceOf(MemberException.class);
            assertThat(((MemberException) thrown).getErrorCode()).isEqualTo(MemberErrorCode.OTP_UNAVAILABLE);
        }
        assertThat(adapter.find(44L)).isEmpty();
    }

    @Test
    @DisplayName("Redis 오류는 AUTH_013 이다 — 저장 · 조회 · 삭제 모두")
    void redisFailure() {
        RedisConnectionFailureException down = new RedisConnectionFailureException("down");
        willThrow(down).given(values).set(anyString(), anyString(), any(Duration.class));
        willThrow(down).given(values).get(anyString());
        willThrow(down).given(redisTemplate).delete(anyString());

        for (Throwable thrown : new Throwable[] {
            catchThrowable(() -> adapter.save(42L, SECRET, TTL)), catchThrowable(() -> adapter.find(42L)), catchThrowable(() -> adapter.delete(42L))
        }) {
            assertThat(thrown).isInstanceOf(MemberException.class);
            assertThat(((MemberException) thrown).getErrorCode()).isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        }
    }
}
