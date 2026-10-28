package com.commercefoundry.domainlayer.member.application.exception;

import java.time.Duration;
import java.util.Objects;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 시도 제한 초과 (#66) — {@link MemberErrorCode#LOGIN_ATTEMPT_LIMITED} · {@link MemberErrorCode#SIGN_UP_ATTEMPT_LIMITED}.
 * 창이 끝날 때까지 남은 시간을 원값({@link Duration})으로만 싣는다 — 헤더 형식(초 올림 · 최소 1초)은 {@code MemberExceptionHandler} 가 {@code Retry-After} 를
 * 만들 때 정한다. 이메일 · 해시는 싣지 않는다.
 */
@Getter
public class LoginAttemptLimitedException extends MemberException {

    /** 창이 끝날 때까지 남은 시간. 0 이상 */
    private final Duration retryAfter;

    /**
     * @param errorCode  HTTP 상태가 429 인 코드만 받는다 — {@code Retry-After} 는 429 · 503 응답에만 뜻이 있고, 이 예외는 429 전용이다
     * @param retryAfter 창이 끝날 때까지 남은 시간. null · 음수면 거절한다
     * @throws IllegalArgumentException errorCode 가 429 가 아니거나 retryAfter 가 음수다
     */
    public LoginAttemptLimitedException(MemberErrorCode errorCode, Duration retryAfter) {
        super(requireTooManyRequests(errorCode));
        Objects.requireNonNull(retryAfter, "retryAfter");
        if (retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter 는 0 이상이어야 한다");
        }
        this.retryAfter = retryAfter;
    }

    private static MemberErrorCode requireTooManyRequests(MemberErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode");
        if (errorCode.getHttpStatus() != HttpStatus.TOO_MANY_REQUESTS) {
            throw new IllegalArgumentException("LoginAttemptLimitedException 은 429 코드만 받는다: " + errorCode.getCode());
        }
        return errorCode;
    }
}
