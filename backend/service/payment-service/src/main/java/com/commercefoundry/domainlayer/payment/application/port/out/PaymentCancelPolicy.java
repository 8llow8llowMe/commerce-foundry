package com.commercefoundry.domainlayer.payment.application.port.out;

/** 커밋 후 비PG 빠른 훅. 실행 보장은 #591, 실제 취소 직전 상태·잔액 재검증은 #579의 책임이다. */
public interface PaymentCancelPolicy {
    void onRejected(long paymentId);
}
