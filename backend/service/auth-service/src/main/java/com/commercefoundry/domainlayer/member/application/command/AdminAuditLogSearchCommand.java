package com.commercefoundry.domainlayer.member.application.command;

import com.commercefoundry.domainlayer.member.application.model.AdminAuditAction;
import com.commercefoundry.domainlayer.member.application.model.AdminAuditResult;

/**
 * 어드민 감사 로그 조회 조건. 형식 검증(양수 · 페이지 범위 · target)은 컨트롤러가 끝냈다.
 *
 * @param action 행위. 없으면 전체
 * @param actorMemberId 행위한 관리자. 없으면 전체
 * @param target 대상 완전 일치. 없으면 전체. 로그인 실패는 행위자가 없어 노린 계정을 여기서 고른다
 * @param result 결과. 없으면 전체
 * @param page 0 부터 센다
 * @param size 1 ~ 100
 */
public record AdminAuditLogSearchCommand(
    AdminAuditAction action,
    Long actorMemberId,
    String target,
    AdminAuditResult result,
    int page,
    int size
) {
}
