package com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.custom;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminAuditLogEntity;
import org.springframework.data.domain.Page;

/**
 * 관리자 감사 로그 조회. 행위 · 관리자 · 대상 · 결과가 각각 켜지고 꺼지므로 파생 쿼리 조합 대신 QueryDSL 이다 ({@code coding-conventions.md} §9-5).
 */
public interface AdminAuditLogCustomRepository {

    /**
     * 아이디 내림차순(최신순). 인자가 null 이면 그 조건은 걸지 않는다. 건수는 같은 조건이다.
     *
     * @param target 대상 완전 일치. {@code member:<id>} 처럼 공백이 없다
     * @param result {@code SUCCESS} · {@code FAILURE}
     */
    Page<AdminAuditLogEntity> findPage(String action, Long actorMemberId, String target, String result, int page, int size);
}
