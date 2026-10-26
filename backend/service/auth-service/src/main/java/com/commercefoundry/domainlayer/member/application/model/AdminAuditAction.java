package com.commercefoundry.domainlayer.member.application.model;

/**
 * 관리자 감사 행위 이름 ({@code security-guide.md} §11-4). 로그 검색 키이자 {@code auth.admin_audit_log.action} 값이라 한번 정한 이름을 바꾸지 않는다.
 * 로그 어댑터 · DB 어댑터 · 어드민 감사 조회 필터가 함께 쓴다.
 */
public enum AdminAuditAction {

    ADMIN_LOGIN,
    MEMBER_SEARCH,
    /** 가게 운영자 계정을 추가했다 (#193). */
    ADMIN_CREATE,
    /** 가게 운영자 계정을 비활성화했다 (#193). */
    ADMIN_DEACTIVATE,
    /** 로그인한 운영자가 자기 비밀번호를 바꿨다 (#193). */
    ADMIN_PASSWORD_CHANGE
}
