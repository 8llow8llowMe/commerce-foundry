package com.commercefoundry.domainlayer.order.application.port.in;

/** 결제 반영 재고 복원 스윕 진입점 (#582) — 사람이 아니라 시스템이 부른다. */
public interface PaymentRestorationSweepUseCase {

    /** 다음 시각이 지난 복원 대기 작업을 한 배치 집어 같은 클레임으로 복원하고 마무리한다 ({@code order-flow-guide.md} §3-6). */
    void sweepDueRestorations();
}
