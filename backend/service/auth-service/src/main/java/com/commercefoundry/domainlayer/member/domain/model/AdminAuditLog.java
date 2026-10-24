package com.commercefoundry.domainlayer.member.domain.model;

import java.time.Instant;

/**
 * 저장된 관리자 감사 로그 한 건 (#192). 읽기 전용이다 — 쓰기는 {@code AdminAuditPort} 가 행위별 메서드로 한다.
 *
 * @param action 행위 이름 (ADMIN_LOGIN · MEMBER_SEARCH …)
 * @param actorMemberId 행위한 관리자. 모르면 null
 * @param target 대상. 없으면 null
 * @param result SUCCESS · FAILURE
 */
public record AdminAuditLog(long id, String action, Long actorMemberId, String target, String result, Instant createdAt) {
}
