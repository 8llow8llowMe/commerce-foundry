package com.commercefoundry.domainlayer.payment.application.port.out;

/**
 * 주문이 결제를 거절한 뒤의 취소 정책 훅 (#308 PR-C, {@code payment-guide.md} §6). 돈은 빠졌는데 주문이 결제 완료가 되지 않았다 — 전액 취소 대상이다.
 *
 * <p><b>at-most-once 빠른 경로다.</b> 기록 트랜잭션이 커밋된 뒤 트랜잭션 밖에서, 처음 거절을 기록한 처리에서만 한 번 부른다(같은 회신의 재전달 · 다른 eventId 의 같은 회신은
 * 부르지 않는다). 커밋과 호출 사이에 프로세스가 죽으면 불리지 않는다. <b>정본 트리거는 DB 상태</b>(거절 코드 {@code order_sync_failure_code} 가 있고 DONE · PARTIAL_CANCELED)이고,
 * 그 상태의 재대조 · 자동 취소는 #591 · #579 다. 놓친 건은 거절 결제 게이지({@code commerce.payment.order-sync.rejected.count})로도 보인다.
 *
 * <p>이미 전액 취소된 결제는 부르지 않는다. 구현은 결제를 <b>다시 읽어 잔액을 확인해야 한다</b> — 커밋과 훅 사이에 취소가 들어올 수 있다.
 * 기본 구현은 ERROR 로그 · 지표만 남긴다. 구현이 예외를 던져도 기록은 되돌리지 않는다(호출자가 로그만 남긴다).
 */
public interface PaymentCancelPolicy {

    /**
     * @param rejectionReason order-service 거절 사유 코드 ({@code ORDER_CANCELED} 등)
     */
    void onOrderRejected(long paymentId, long orderId, String rejectionReason);
}
