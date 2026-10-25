package com.commercefoundry.domainlayer.member.application.port.out;

import java.util.Collection;
import java.util.List;

/**
 * 보관 기간이 지난 관리자 감사 로그 삭제 (#286). 조회 · 추가({@link AdminAuditLogRepositoryPort} · {@link AdminAuditPort})와 나눈다 —
 * DELETE 문장은 이 포트 뒤에만 있다.
 */
public interface AdminAuditLogRetentionRepositoryPort {

    /** {@code cutoffId} 보다 작은 아이디를 오름차순으로 {@code limit} 건까지. 없으면 빈 목록. */
    List<Long> findIdsOlderThan(long cutoffId, int limit);

    /**
     * 아이디 목록을 한 문장으로 지운다. 비어 있으면 쿼리를 보내지 않고 0 이다.
     *
     * @return 지워진 행 수
     */
    int deleteByIds(Collection<Long> ids);
}
