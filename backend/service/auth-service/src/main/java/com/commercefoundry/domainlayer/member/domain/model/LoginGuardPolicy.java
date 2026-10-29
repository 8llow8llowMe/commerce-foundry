package com.commercefoundry.domainlayer.member.domain.model;

import java.util.Objects;
import java.time.Duration;

/**
 * 시도 제한 전체 정책 — 켜짐 여부와 종류별 {@link LoginAttemptPolicy} (#66). 로그인 두 종류는 한 정책을 나눠 쓰고, 회원가입은 따로 둔다.
 *
 * @param enabled 끄면 아무것도 세지 않는다 — 부르는 쪽은 저장소를 부르지 않는다
 * @param login   고객 · 관리자 로그인의 허용 횟수 · 창. 두 로그인은 값만 같고 창은 따로 센다({@link AttemptKind#ADMIN_LOGIN})
 * @param signUp  회원가입의 허용 횟수 · 창
 */
public record LoginGuardPolicy(boolean enabled, LoginAttemptPolicy login, LoginAttemptPolicy signUp, LoginAttemptPolicy signUpIp) {

    public LoginGuardPolicy(boolean enabled, LoginAttemptPolicy login, LoginAttemptPolicy signUp) {
        this(enabled, login, signUp, new LoginAttemptPolicy(30, Duration.ofMinutes(10)));
    }

    public LoginGuardPolicy {
        Objects.requireNonNull(login, "login");
        Objects.requireNonNull(signUp, "signUp");
        Objects.requireNonNull(signUpIp, "signUpIp");
    }

    /** 그 종류에 쓰는 허용 횟수 · 창. */
    public LoginAttemptPolicy of(AttemptKind kind) {
        return switch (kind) {
            case LOGIN, ADMIN_LOGIN, ADMIN_OTP -> login;
            case SIGN_UP -> signUp;
            case SIGN_UP_IP -> signUpIp;
        };
    }
}
