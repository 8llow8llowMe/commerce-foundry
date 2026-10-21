package com.commercefoundry.domainlayer.member.application.exception;

import java.time.Duration;
import java.util.Objects;
import lombok.Getter;

/**
 * 시도 제한 초과 (#66) — {@link MemberErrorCode#LOGIN_ATTEMPT_LIMITED} · {@link MemberErrorCode#SIGN_UP_ATTEMPT_LIMITED}.
 * 창이 끝날 때까지 남은 시간을 싣는다 — {@code MemberExceptionHandler} 가 {@code Retry-After}(초, 올림, 최소 1)로 바꾼다.
 * 이메일 · 해시는 싣지 않는다.
 */
@Getter
public class LoginAttemptLimitedException extends MemberException {

    /** 창이 끝날 때까지 남은 시간. 0 이상 */
    private final Duration retryAfter;

    public LoginAttemptLimitedException(MemberErrorCode errorCode, Duration retryAfter) {
        super(errorCode);
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    /**
     * {@code Retry-After} 헤더 값(초) — {@code max(1, ceil(ms / 1000))}. 창 끝과 같은 시각(남은 0ms)도 1초다: 0 이면 클라이언트가 바로 다시 보내
     * 같은 창에 부딪힌다.
     */
    public long retryAfterSeconds() {
        long millis = Math.max(0, retryAfter.toMillis());
        return Math.max(1, (millis + 999) / 1000);
    }
}
