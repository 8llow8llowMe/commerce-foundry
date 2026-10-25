package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.application.port.in.AdminAuditLogRetentionSchedulerUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditLogRetentionProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보관 정리의 트랜잭션 경계. 스케줄러(다른 빈)가 이 Facade 의 프록시를 거쳐 배치마다 부르므로 {@link #deleteBatch} 가 배치마다 새 트랜잭션이다.
 * 컷오프 계산은 DB 를 보지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdminAuditLogRetentionSchedulerFacade implements AdminAuditLogRetentionSchedulerUseCase {

    private final AdminAuditLogRetentionProcessor adminAuditLogRetentionProcessor;

    @Override
    public long cutoffId() {
        return adminAuditLogRetentionProcessor.cutoffId();
    }

    @Override
    @Transactional
    public int deleteBatch(long cutoffId, int batchSize) {
        return adminAuditLogRetentionProcessor.deleteBatch(cutoffId, batchSize);
    }
}
