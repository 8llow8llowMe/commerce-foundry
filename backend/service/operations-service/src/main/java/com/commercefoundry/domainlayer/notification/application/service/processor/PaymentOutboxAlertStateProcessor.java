package com.commercefoundry.domainlayer.notification.application.service.processor;

import com.commercefoundry.domainlayer.notification.application.port.out.OutboxAlertStatePort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRepositoryPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentOutboxAlertPushPort;
import com.commercefoundry.domainlayer.notification.application.info.PaymentOutboxObservationInfo;
import com.commercefoundry.domainlayer.notification.domain.model.OutboxAlertState;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationRequest;
import com.commercefoundry.global.properties.PaymentOutboxAlertProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
public class PaymentOutboxAlertStateProcessor {
    private final OutboxAlertStatePort states;
    private final NotificationRepositoryPort notifications;
    private final PaymentOutboxAlertPushPort push;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;
    private final PaymentOutboxAlertProperties properties;
    private final LongSupplier ticker;

    public PaymentOutboxAlertStateProcessor(OutboxAlertStatePort states, NotificationRepositoryPort notifications, PaymentOutboxAlertPushPort push,
                                           SnowflakeIdGenerator ids, Clock clock, PaymentOutboxAlertProperties properties,
                                           @Qualifier("paymentOutboxAlertTicker") LongSupplier ticker) {
        this.states = states;
        this.notifications = notifications;
        this.push = push;
        this.ids = ids;
        this.clock = clock;
        this.properties = properties;
        this.ticker = ticker;
    }

    @Transactional(timeout = 15)
    public void accept(PaymentOutboxObservationInfo sample, List<Long> recipients, Instant beforeHttp, Instant afterHttp, long started) {
        if (recipients.isEmpty() || recipients.size() > 1000) {
            throw new IllegalArgumentException("Invalid recipients");
        }
        var previous = states.findLocked().orElse(null);
        var now = clock.instant();
        long elapsed = ticker.getAsLong() - started;
        if (afterHttp.isBefore(beforeHttp) || now.isBefore(afterHttp) || elapsed < 0 || elapsed > properties.maxObservationAge().toNanos()) {
            log.warn("Payment outbox observation rejected: local timing invalid");
            return;
        }
        var decision = OutboxAlertState.accept(previous, sample.observedAt(), sample.readyUnpublishedCount(), sample.oldestReadyAvailableAt(),
            now, properties.threshold(), properties.suppression(), properties.maxObservationAge());
        if (decision.state() == previous) {
            log.warn("Payment outbox observation rejected: freshness or watermark invalid");
            return;
        }
        states.save(decision.state(), previous == null);
        if (decision.signal() == OutboxAlertState.Signal.NONE) {
            return;
        }
        boolean stalled = decision.signal() == OutboxAlertState.Signal.STALLED;
        var saved = new ArrayList<Notification>();
        for (long recipient : recipients) {
            long id = ids.generateId();
            saved.add(notifications.insert(Notification.create(id, new NotificationRequest(NotificationKind.ANOMALY,
                stalled ? NotificationSeverity.CRITICAL : NotificationSeverity.INFO,
                stalled ? "결제 이벤트 발행이 지연되고 있습니다. 담당자는 즉시 점검해 주세요." : "결제 이벤트 발행 대기가 해소되었습니다.",
                "/admin/payments", recipient, "PAYMENT_OUTBOX_ALERT", id))));
        }
        var batch = List.copyOf(saved);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    push.enqueue(batch);
                } catch (RuntimeException ignored) {
                    log.warn("Payment outbox notification push unavailable; persisted notification remains available");
                }
            }
        });
    }
}
