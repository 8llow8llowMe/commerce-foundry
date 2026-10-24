package com.commercefoundry.domainlayer.member.application.model;

/**
 * 관리자 감사 행위 이름 ({@code security-guide.md} §11-4). 로그 검색 키이자 {@code auth.admin_audit_log.action} 값이라 한번 정한 이름을 바꾸지 않는다.
 * 로그 어댑터 · DB 어댑터 · 어드민 감사 조회 필터가 함께 쓴다.
 */
public enum AdminAuditAction {

    ADMIN_LOGIN,
    MEMBER_SEARCH
}
