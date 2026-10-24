package com.commercefoundry.domainlayer.member.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminAuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * 관리자 감사 로그 (#192). 추가와 조회만 연다 — {@code JpaRepository} 를 상속하지 않아 {@code delete*} · {@code saveAll} 같은 메서드가 없다(append-only).
 * 조회는 조건 조합마다 파생 쿼리 하나, 모두 아이디 내림차순(최신순)이다.
 */
public interface AdminAuditLogRepository extends Repository<AdminAuditLogEntity, Long> {

    /** 새 행 하나. {@code Persistable} 이라 SELECT 없이 persist 하고, 바로 flush 해 제약 위반을 이 호출에서 드러낸다. */
    AdminAuditLogEntity saveAndFlush(AdminAuditLogEntity entity);

    /** 조건 없음 — PK 역순. */
    Page<AdminAuditLogEntity> findAllByOrderByIdDesc(Pageable pageable);

    /** 행위 조건 — {@code idx_admin_audit_log_action_id}. */
    Page<AdminAuditLogEntity> findAllByActionOrderByIdDesc(String action, Pageable pageable);

    /** 관리자 조건 — {@code idx_admin_audit_log_actor_member_id_id}. */
    Page<AdminAuditLogEntity> findAllByActorMemberIdOrderByIdDesc(Long actorMemberId, Pageable pageable);

    /** 행위 · 관리자 조건 — 관리자 인덱스로 거른 뒤 행위를 본다(한 관리자의 행은 많지 않다). */
    Page<AdminAuditLogEntity> findAllByActionAndActorMemberIdOrderByIdDesc(String action, Long actorMemberId, Pageable pageable);
}
