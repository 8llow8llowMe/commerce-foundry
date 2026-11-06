package com.commercefoundry.domainlayer.notification.application.service;

import com.commercefoundry.domainlayer.notification.application.model.PaymentAnomalyObservationTiming;
import com.commercefoundry.domainlayer.notification.application.port.in.PaymentAnomalyAlertSchedulerUseCase;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyAlertPushProcessor;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyAlertStateProcessor;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyObservationProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyObservation;
import com.commercefoundry.global.properties.PaymentAnomalyAlertProperties;
import java.time.Clock;
import java.util.List;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class PaymentAnomalyAlertSchedulerFacade implements PaymentAnomalyAlertSchedulerUseCase {
    private final PaymentAnomalyObservationProcessor observations;
    private final PaymentAnomalyAlertStateProcessor states;
    private final PaymentAnomalyAlertPushProcessor push;
    private final PaymentAnomalyAlertProperties properties;
    private final Clock clock;
    private final LongSupplier ticker;

    public PaymentAnomalyAlertSchedulerFacade(PaymentAnomalyObservationProcessor observations, PaymentAnomalyAlertStateProcessor states,
                                              PaymentAnomalyAlertPushProcessor push, PaymentAnomalyAlertProperties properties,
                                              Clock clock, @Qualifier("paymentAnomalyTicker") LongSupplier ticker) {
        this.observations = observations;
        this.states = states;
        this.push = push;
        this.properties = properties;
        this.clock = clock;
        this.ticker = ticker;
    }

    /** 원격 I/O 동안 호출자 TX도 중단한다. 저장 프록시의 실제 커밋 정상 반환 뒤에만 최선 전달을 요청한다. */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void observe() {
        if (!properties.enabled()) { return; }
        try {
            observations.runLocked(this::collect);
        } catch (RuntimeException ignored) {
            log.warn("payment_anomaly_collection_failed reason=lock");
        }
    }

    private void collect() {
        List<Long> recipients;
        try {
            recipients = observations.recipients();
        } catch (RuntimeException ignored) {
            log.warn("payment_anomaly_collection_failed reason=recipient");
            return;
        }
        PaymentAnomalyObservation sample;
        PaymentAnomalyObservationTiming timing;
        try {
            var before = clock.instant();
            long started = ticker.getAsLong();
            sample = observations.observe();
            timing = new PaymentAnomalyObservationTiming(before, clock.instant(), started);
        } catch (RuntimeException ignored) {
            log.warn("payment_anomaly_collection_failed reason=source");
            return;
        }
        List<Notification> saved;
        try {
            saved = states.accept(sample, recipients, properties.settings(), timing);
        } catch (RuntimeException ignored) {
            log.warn("payment_anomaly_collection_failed reason=storage");
            return;
        }
        // 최초 정상·억제도 빈 결과이므로 빈 목록만으로 시간 검증 거부로 단정하지 않는다.
        if (saved.isEmpty()) { return; }
        try {
            push.enqueue(saved);
        } catch (RuntimeException ignored) {
            log.warn("payment_anomaly_push_failed reason=postcommit");
        }
    }
}
