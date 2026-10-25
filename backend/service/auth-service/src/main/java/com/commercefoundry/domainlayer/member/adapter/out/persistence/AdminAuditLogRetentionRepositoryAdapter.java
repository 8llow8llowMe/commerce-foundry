package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.AdminAuditLogRetentionRepository;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditLogRetentionRepositoryPort;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** 보관 정리 JPQL (#286). 앱의 다른 경로는 이 리포지토리를 쓰지 않는다. */
@Component
@RequiredArgsConstructor
public class AdminAuditLogRetentionRepositoryAdapter implements AdminAuditLogRetentionRepositoryPort {

    private final AdminAuditLogRetentionRepository adminAuditLogRetentionRepository;

    @Override
    public List<Long> findIdsOlderThan(long cutoffId, int limit) {
        return List.copyOf(adminAuditLogRetentionRepository.findIdsOlderThan(cutoffId, PageRequest.of(0, limit)));
    }

    @Override
    public int deleteByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return adminAuditLogRetentionRepository.deleteByIds(ids);
    }
}
