package com.commercefoundry.domainlayer.payment.application.port.in;

/** 스케줄러가 호출하는 제한된 결제 재조회 복구 계약. */
public interface PaymentRecoveryUseCase {
    void recover();
}
