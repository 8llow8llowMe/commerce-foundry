package com.commercefoundry.domainlayer.payment.application.port.out.query;

import com.commercefoundry.commerce.support.persistence.MoneyColumns;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;

/** 결제 · 외부 확인 환불 상태 · 통화별 취소 원장 합계 한 줄 (#693). 클레임 아이디 · 개별 행은 싣지 않는다. */
public record PaymentAdminExternalRefundQueryResult(long paymentId, PaymentCancel.Status status, long amount, CurrencyCode currency) {
    public Money money() {
        return MoneyColumns.required(amount, currency);
    }
}
