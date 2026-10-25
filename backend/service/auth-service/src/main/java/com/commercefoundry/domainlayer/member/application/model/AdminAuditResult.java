package com.commercefoundry.domainlayer.member.application.model;

/**
 * 관리자 감사 결과 ({@code security-guide.md} §11-4). {@code auth.admin_audit_log.result} 와 같은 이름이라 바꾸지 않는다.
 * 실패 이유(회원 없음 · 비밀번호 틀림)는 나누지 않는다.
 */
public enum AdminAuditResult {

    SUCCESS,
    FAILURE
}
