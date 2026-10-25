package com.commercefoundry.domainlayer.member.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminAuditLogEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * 관리자 감사 로그 보관 정리 (#286). 추가 · 조회({@link AdminAuditLogRepository})와 나눈다 — DELETE 는 여기만 연다.
 * 범위는 Snowflake 아이디다. {@code created_at} 인덱스를 두지 않는다 ({@code security-guide.md} §11-4).
 */
public interface AdminAuditLogRetentionRepository extends Repository<AdminAuditLogEntity, Long> {

    /** 컷오프보다 이전인 아이디. PK 오름차순이라 다음 배치는 남은 가장 오래된 행부터 읽는다. */
    @Query("select e.id from AdminAuditLogEntity e where e.id < :cutoffId order by e.id asc")
    List<Long> findIdsOlderThan(long cutoffId, Pageable pageable);

    /** 고른 아이디만 한 문장으로 지운다. 빈 목록은 어댑터가 여기서 부르지 않는다 — {@code in ()} 는 SQL 이 거절한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AdminAuditLogEntity e where e.id in :ids")
    int deleteByIds(Collection<Long> ids);
}
