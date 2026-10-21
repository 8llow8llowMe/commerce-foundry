package com.commercefoundry.domainlayer.member.domain.model;

import java.time.Duration;
import java.util.Objects;

/**
 * 로그인 · 회원가입 시도 제한 정책 — 고정 창 하나에 허용하는 시도 수와 창의 길이 (#66). 범위는 프로퍼티 {@code auth.login-guard.*} 와 같다
 * ({@code AuthLoginGuardProperties} 가 이 상수를 쓴다).
 *
 * @param maxAttempts 창 하나에 허용하는 시도 수. {@code maxAttempts + 1} 번째 시도부터 넘긴 것으로 본다
 * @param window      첫 시도부터 재는 창의 길이. 이어지는 시도가 창을 늘리지 않는다
 */
public record LoginAttemptPolicy(int maxAttempts, Duration window) {

    /** 허용 시도 수의 상한. 크게 잡을수록 창마다 비밀번호 추측 · BCrypt 를 그만큼 더 허용해 제한이 뜻을 잃는다. */
    public static final int MAX_ATTEMPTS_CAP = 100;

    /** 창의 하한. 짧으면 추측 속도가 거의 줄지 않는다. */
    public static final Duration MIN_WINDOW = Duration.ofMinutes(1);

    /** 창의 상한. 길수록 한도를 넘긴 이메일이 오래 묶인다. */
    public static final Duration MAX_WINDOW = Duration.ofHours(24);

    public LoginAttemptPolicy {
        Objects.requireNonNull(window, "window");
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_CAP) {
            throw new IllegalArgumentException("maxAttempts 는 1 이상 " + MAX_ATTEMPTS_CAP + " 이하여야 한다");
        }
        if (window.compareTo(MIN_WINDOW) < 0 || window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("window 는 1분 이상 24시간 이하여야 한다");
        }
    }
}
