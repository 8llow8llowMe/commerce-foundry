package com.commercefoundry.domainlayer.member.application.model;

/**
 * 관리자 감사 행위 이름 ({@code security-guide.md} §11-4). 로그 검색 키이자 {@code auth.admin_audit_log.action} 값이라 한번 정한 이름을 바꾸지 않는다.
 * 로그 어댑터 · DB 어댑터 · 어드민 감사 조회 필터가 함께 쓴다.
 */
public enum AdminAuditAction {

    ADMIN_LOGIN,
    /**
     * OTP 를 등록한 관리자가 로그인 1단계(비밀번호)를 통과해 2단계 챌린지를 받았다 (#238 PR-B). 로그인 성공({@code ADMIN_LOGIN})과 다르다 — 아직 토큰이 나가지 않았다.
     * 성공 행만 있다(1단계 실패는 {@code ADMIN_LOGIN} 실패다). 행위자는 모르는 것으로({@code -}) 두고 대상이 {@code member:<id>} 다 — 인증이 끝나지 않았다.
     * 이 줄이 늘어나는데 {@code ADMIN_LOGIN} 성공이 따라오지 않으면 비밀번호가 새어 2단계를 대입하는 중일 수 있다.
     */
    ADMIN_LOGIN_PASSWORD_VERIFIED,
    MEMBER_SEARCH,
    /** 가게 운영자 계정을 추가했다 (#193). */
    ADMIN_CREATE,
    /** 가게 운영자 계정을 비활성화했다 (#193). */
    ADMIN_DEACTIVATE,
    /** 로그인한 운영자가 자기 비밀번호를 바꿨다 (#193). */
    ADMIN_PASSWORD_CHANGE,
    /** 로그인한 운영자가 OTP 등록을 확인했다 (#238). 등록 시작(비밀 발급)은 남기지 않는다 — 확인 전에는 아무것도 켜지지 않는다. */
    ADMIN_OTP_ENROLL,
    /** 로그인한 운영자가 자기 OTP 를 해제했다 (#238). */
    ADMIN_OTP_DISABLE,
    /** 로그인한 운영자가 OTP 복구 코드를 다시 발급했다 (#238). 이전 코드는 모두 무효다. */
    ADMIN_OTP_RECOVERY_REGENERATE
}
