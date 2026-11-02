package com.commercefoundry.domainlayer.payment.application.port.in;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelResponse;
import com.commercefoundry.shared.commerce.CurrencyCode;

public interface PaymentCancellationUseCase {
    PaymentCancelResponse cancel(long paymentId, long claimId, long amount, CurrencyCode currency, String idempotencyKey);
}
