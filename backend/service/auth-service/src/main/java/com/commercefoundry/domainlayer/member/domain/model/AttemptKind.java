package com.commercefoundry.domainlayer.member.domain.model;

/**
 * 시도 제한을 따로 세는 종류 (#66). 같은 이메일이라도 종류마다 창을 따로 쓴다 — 한쪽 시도가 다른 쪽 한도를 깎지 않는다.
 * 저장소의 키 이름은 구현이 이 값에서 정한다.
 */
public enum AttemptKind {

    /** 로그인 — 같은 이메일로 비밀번호를 반복해 맞혀 보는 시도를 센다. */
    LOGIN,

    /**
     * 관리자 로그인(#6) — 고객 로그인과 창을 나눈다. 한 창이면 고객 로그인 경로({@code /api/v1/auth/login})에 관리자 이메일을 거듭 보내는 것만으로
     * 관리자 로그인을 잠글 수 있다. 허용 횟수 · 창은 고객 로그인과 같은 값을 쓴다.
     */
    ADMIN_LOGIN,

    /** 회원가입 — 같은 이메일로 반복하는 가입 요청(중복 확인 폴링 · BCrypt 반복)을 센다. */
    SIGN_UP
}
