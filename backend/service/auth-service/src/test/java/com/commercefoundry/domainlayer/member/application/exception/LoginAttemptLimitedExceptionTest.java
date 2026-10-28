package com.commercefoundry.domainlayer.member.application.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

/**
 * 시도 제한 예외의 생성자 방어 — 429 코드만 받고, 남은 시간은 0 이상 원값으로 싣는다. 헤더 형식(초 변환)은 핸들러 몫이라 여기서 보지 않는다.
 */
class LoginAttemptLimitedExceptionTest {

    @ParameterizedTest
    @EnumSource(value = MemberErrorCode.class, names = {"LOGIN_ATTEMPT_LIMITED", "SIGN_UP_ATTEMPT_LIMITED"})
    @DisplayName("429 코드는 받고 남은 시간을 원값 그대로 싣는다 — 메시지는 코드의 고정 문구다")
    void acceptsTooManyRequestsCodes(MemberErrorCode errorCode) {
        LoginAttemptLimitedException limited = new LoginAttemptLimitedException(errorCode, Duration.ofMillis(1500));

        assertThat(limited.getErrorCode()).isEqualTo(errorCode);
        assertThat(limited.getRetryAfter()).isEqualTo(Duration.ofMillis(1500));
        assertThat(limited.getMessage()).isEqualTo(errorCode.getMessage());
    }

    @Test
    @DisplayName("남은 0ms(창 끝과 같은 시각)도 받는다")
    void acceptsZero() {
        assertThat(new LoginAttemptLimitedException(MemberErrorCode.LOGIN_ATTEMPT_LIMITED, Duration.ZERO).getRetryAfter()).isZero();
    }

    @Test
    @DisplayName("429 가 아닌 코드는 IllegalArgumentException 이다 — Retry-After 를 붙일 응답이 아니다")
    void rejectsNonTooManyRequestsCodes() {
        assertThat(Arrays.stream(MemberErrorCode.values()).filter(code -> code.getHttpStatus() != HttpStatus.TOO_MANY_REQUESTS))
            .isNotEmpty()
            .allSatisfy(code -> assertThatThrownBy(() -> new LoginAttemptLimitedException(code, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(code.getCode()));
    }

    @Test
    @DisplayName("음수 남은 시간은 IllegalArgumentException, null 코드 · null 남은 시간은 NullPointerException 이다")
    void rejectsNegativeAndNull() {
        assertThatThrownBy(() -> new LoginAttemptLimitedException(MemberErrorCode.LOGIN_ATTEMPT_LIMITED, Duration.ofMillis(-1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptLimitedException(MemberErrorCode.LOGIN_ATTEMPT_LIMITED, null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("retryAfter");
        assertThatThrownBy(() -> new LoginAttemptLimitedException(null, Duration.ofSeconds(1)))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("errorCode");
    }
}
