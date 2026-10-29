package com.commercefoundry.domainlayer.aiusage.application.port.in;

/** 원장 정리 — 결과가 오지 않은 예약의 회수와 오래된 호출 행 삭제. 스케줄러(PR-B3)가 부른다. 여러 인스턴스가 같이 돌려도 한 번만 반영된다. */
public interface AiUsageMaintenanceUseCase {

    /**
     * 예약 뒤 {@code operations.ai.reservation-sweep.stale-after} 가 지나도 PENDING 인 호출을 한 배치 ABANDONED 로 닫는다(예상치를 비용으로 남긴다).
     *
     * @return 닫은 건수
     */
    int abandonStale();

    /**
     * 호출 시각이 보존 기간보다 오래된 호출 행을 지운다. 월 카운터는 남는다.
     *
     * @return 지운 건수
     */
    int deleteExpired();
}
