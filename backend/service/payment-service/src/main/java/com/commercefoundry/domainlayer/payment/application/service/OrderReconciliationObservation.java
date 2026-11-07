package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.application.model.OrderPaymentReplySnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 성공 커밋으로 변경한 재대조만 집계한다. 실제 DLT 발행 counter와 별개다. */
@Component
@RequiredArgsConstructor
public class OrderReconciliationObservation {
    public enum Result {
        PAID, REJECTED_ORDER_CANCELED, REJECTED_ORDER_NOT_FOUND, REJECTED_PAYMENT_MISMATCH,
        REJECTED_STOCK_UNAVAILABLE, REJECTED_ALREADY_PAID, REJECTED_PAYMENT_NOT_DONE;

        public static Result from(OrderPaymentReplySnapshot snapshot) {
            return snapshot.paid() ? PAID : valueOf("REJECTED_" + snapshot.rejectionReason());
        }
    }
    public static final String CHANGED = "commerce.payment.order-sync.reconciled";
    private final ObjectProvider<MeterRegistry> registry;

    public void changed(Result result) {
        registry.ifAvailable(r -> r.counter(CHANGED, "result", result.name()).increment());
    }
}
