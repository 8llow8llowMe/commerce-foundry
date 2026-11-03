package com.commercefoundry.domainlayer.notification.application.service;

import com.commercefoundry.domainlayer.notification.application.port.in.PaymentOutboxAlertSchedulerUseCase;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentOutboxAlertStateProcessor;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentOutboxObservationProcessor;
import java.time.Clock;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class PaymentOutboxAlertSchedulerFacade implements PaymentOutboxAlertSchedulerUseCase {
    private final PaymentOutboxObservationProcessor observations;
    private final PaymentOutboxAlertStateProcessor states;
    private final Clock clock;
    private final LongSupplier ticker;

    public PaymentOutboxAlertSchedulerFacade(PaymentOutboxObservationProcessor observations, PaymentOutboxAlertStateProcessor states,
                                            Clock clock, @Qualifier("paymentOutboxAlertTicker") LongSupplier ticker) {
        this.observations = observations;
        this.states = states;
        this.clock = clock;
        this.ticker = ticker;
    }
    // Remote recipient and source calls run outside DB transactions; only final state and fanout are transactional.
    @Override
    public void observe() {
        observations.runLocked(() -> {
            var recipients = observations.recipients();
            var before = clock.instant();
            long started = ticker.getAsLong();
            var sample = observations.observe();
            var after = clock.instant();
            states.accept(sample, recipients, before, after, started);
        });
    }
}
