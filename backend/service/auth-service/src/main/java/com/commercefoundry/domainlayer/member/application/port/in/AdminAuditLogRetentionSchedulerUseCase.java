package com.commercefoundry.domainlayer.member.application.port.in;

/**
 * 관리자 감사 로그 보관 정리 (스케줄러 진입점, #286). 컷오프를 한 번 정하고, 그 아이디보다 작은 행을 배치마다 트랜잭션을 나눠 지운다.
 */
public interface AdminAuditLogRetentionSchedulerUseCase {

    /** 지금 시각에서 보관 기간을 뺀 밀리초의 첫 Snowflake 아이디. 이 값보다 작은 행이 삭제 대상이다. */
    long cutoffId();

    /**
     * 컷오프보다 작은 아이디를 오름차순으로 {@code batchSize} 건까지 골라 지운다.
     *
     * @return 이번 배치에서 고른 건수. 대상이 더 없으면 0 이고, 가득 차지 않으면 남은 행이 없다
     */
    int deleteBatch(long cutoffId, int batchSize);
}
