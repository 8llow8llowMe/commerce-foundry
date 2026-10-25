package com.commercefoundry.domainlayer.member.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminAuditLogEntity;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.custom.AdminAuditLogCustomRepository;
import org.springframework.data.repository.Repository;

/**
 * 관리자 감사 로그 (#192). 추가와 조회만 연다 — {@code JpaRepository} 를 상속하지 않아 {@code delete*} · {@code saveAll} 이 없다.
 * 보관 기간 삭제는 {@code AdminAuditLogRetentionRepository} 만 연다 (#286). 조회는 조건이 켜지고 꺼지므로 QueryDSL 이다.
 */
public interface AdminAuditLogRepository extends Repository<AdminAuditLogEntity, Long>, AdminAuditLogCustomRepository {

    /** 새 행 하나. {@code Persistable} 이라 SELECT 없이 persist 하고, 바로 flush 해 제약 위반을 이 호출에서 드러낸다. */
    AdminAuditLogEntity saveAndFlush(AdminAuditLogEntity entity);
}
