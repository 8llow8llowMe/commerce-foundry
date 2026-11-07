package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException.Reason;

/** 저장소·커밋 예외를 원문과 cause 없는 폐쇄된 내부 분류로 바꾼다. */
public interface OrderPaymentReplyFailurePort {
    Reason classify(RuntimeException failure);
}
