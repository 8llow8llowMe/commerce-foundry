package com.commercefoundry.domainlayer.member.domain.model;

import java.util.Objects;

/**
 * 시도 제한 전체 정책 — 켜짐 여부와 종류별 {@link LoginAttemptPolicy} (#66). 프로퍼티 {@code auth.login-guard.*} 에서 만든다({@code AuthLoginGuardBeansConfig}).
 *
 * @param enabled 끄면 아무것도 세지 않는다 — 부르는 쪽은 저장소를 부르지 않는다
 * @param login   고객 · 관리자 로그인의 허용 횟수 · 창. 두 로그인은 값만 같고 창은 따로 센다({@link AttemptKind#ADMIN_LOGIN})
 * @param signUp  회원가입의 허용 횟수 · 창
 */
public record LoginGuardPolicy(boolean enabled, LoginAttemptPolicy login, LoginAttemptPolicy signUp) {

    public LoginGuardPolicy {
        Objects.requireNonNull(login, "login");
        Objects.requireNonNull(signUp, "signUp");
    }

    /** 그 종류에 쓰는 허용 횟수 · 창. */
    public LoginAttemptPolicy of(AttemptKind kind) {
        return switch (kind) {
            case LOGIN, ADMIN_LOGIN -> login;
            case SIGN_UP -> signUp;
        };
    }
}
