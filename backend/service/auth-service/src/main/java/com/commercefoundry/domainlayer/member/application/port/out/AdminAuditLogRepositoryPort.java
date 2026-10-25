package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.domainlayer.member.domain.model.AdminAuditLog;
import org.springframework.data.domain.Page;

/**
 * 저장된 관리자 감사 로그 조회 (#192). 쓰기는 {@link AdminAuditPort} 다 — 이 포트에는 읽기만 둔다(append-only, 고치거나 지우는 메서드 없음).
 */
public interface AdminAuditLogRepositoryPort {

    /**
     * 아이디(Snowflake) 내림차순, 곧 최신순 한 페이지. 마지막 페이지를 넘긴 요청은 빈 목록이고 전체 건수는 그대로다.
     * null 인 조건은 걸지 않는다.
     *
     * @param action 행위 이름. 없으면 전체
     * @param actorMemberId 행위한 관리자. 없으면 전체
     * @param target 대상 완전 일치. 없으면 전체. 로그인 실패는 행위자가 없어 노린 계정을 여기서 고른다
     * @param result {@code SUCCESS} · {@code FAILURE}. 없으면 전체
     * @param page 0 부터 센다
     */
    Page<AdminAuditLog> findPage(String action, Long actorMemberId, String target, String result, int page, int size);
}
