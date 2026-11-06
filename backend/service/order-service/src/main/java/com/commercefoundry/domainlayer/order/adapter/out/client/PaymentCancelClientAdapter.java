package com.commercefoundry.domainlayer.order.adapter.out.client;

import com.commercefoundry.domainlayer.order.adapter.out.client.feign.PaymentCancelClient;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.PaymentCancelInternalRequest;
import com.commercefoundry.domainlayer.order.adapter.out.client.support.PaymentCancelInternalResponseSupport;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentCancelPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelQueryResult;
import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentCancelClientAdapter implements PaymentCancelPort {

    private final PaymentCancelClient paymentCancelClient;
    private final PaymentCancelInternalResponseSupport paymentCancelInternalResponseSupport;

    /** {@code Idempotency-Key} 는 클레임 아이디의 10진 문자열이다 — payment 가 본문의 {@code claimId} 와 같은지 본다. */
    @Override
    public PaymentCancelQueryResult cancel(long paymentId, long claimId, Money amount) {
        Objects.requireNonNull(amount, "amount");
        if (paymentId <= 0 || claimId <= 0 || amount.isZero()) {
            throw new IllegalArgumentException("positive paymentId, claimId and amount required");
        }
        PaymentCancelInternalRequest request = new PaymentCancelInternalRequest(claimId, amount.amount(), amount.currency());
        return paymentCancelInternalResponseSupport.cancel(paymentId, claimId, amount.amount(),
            () -> paymentCancelClient.cancel(paymentId, Long.toString(claimId), request));
    }
}
