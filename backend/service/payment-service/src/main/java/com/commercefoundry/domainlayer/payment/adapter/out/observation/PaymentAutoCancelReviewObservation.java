package com.commercefoundry.domainlayer.payment.adapter.out.observation;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentAutoCancelRepository;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentAutoCancelReviewPort;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAutoCancel;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Payment-side observation only; never sends an operations/admin notification. */
@Slf4j
@Component
public class PaymentAutoCancelReviewObservation implements PaymentAutoCancelReviewPort {
    public static final String COUNTER = "commerce.payment.auto-cancel.manual-review";
    public static final String GAUGE = "commerce.payment.auto-cancel.manual-review.pending";
    private final PaymentAutoCancelRepository repository;
    private final ObjectProvider<MeterRegistry> registries;

    public PaymentAutoCancelReviewObservation(PaymentAutoCancelRepository repository, ObjectProvider<MeterRegistry> registries) {
        this.repository = repository;
        this.registries = registries;
        registries.ifAvailable(r -> Gauge.builder(GAUGE, this, PaymentAutoCancelReviewObservation::outstanding).strongReference(true).register(r));
    }
    @Override
    public void afterCommit() {
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { emit(); }
            });
        }
        // Without a caller transaction there is no commit to observe; the durable gauge remains the source of truth.
    }
    private void emit() {
        log.error("Payment automatic cancellation requires review: AUTO_CANCEL_MANUAL_REVIEW");
        try {
            registries.ifAvailable(r -> r.counter(COUNTER).increment());
        } catch (RuntimeException ignored) {
            // Observation failure must not change a committed payment result. The durable gauge remains available.
        }
    }
    private double outstanding() {
        try {
            return repository.countByState(PaymentAutoCancel.State.MANUAL_REVIEW);
        } catch (RuntimeException ignored) {
            return Double.NaN; // Missing metrics source is not reported as zero manual-review work.
        }
    }
}
