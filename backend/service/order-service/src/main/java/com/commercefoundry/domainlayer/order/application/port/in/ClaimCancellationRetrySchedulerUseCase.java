package com.commercefoundry.domainlayer.order.application.port.in;

/** 결제 후 취소 클레임 재시도 스케줄러 진입점 (#16 PR-2) — 사람이 아니라 시스템이 부른다. */
public interface ClaimCancellationRetrySchedulerUseCase {

    /** 다음 시각이 지난 환불 대기 · 복원 대기 취소 클레임을 한 배치 집어 남은 단계를 다시 실행한다 ({@code order-flow-guide.md} §5). */
    void retryDueCancellations();
}
