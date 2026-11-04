package com.commercefoundry.domainlayer.notification.application.service.processor;

import com.commercefoundry.domainlayer.notification.application.model.PaymentAnomalyObservationTiming;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationBatchInsertPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertStatePort;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationRequest;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyObservation;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicy;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicySettings;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** E2 내부 저장 경계. 원천 HTTP/수신자 조회와 커밋 후 전달은 후속 연결 계층의 책임이다. */
@Component
public class PaymentAnomalyAlertStateProcessor {
    private final PaymentAnomalyAlertStatePort states;
    private final NotificationBatchInsertPort notifications;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;
    private final LongSupplier ticker;

    public PaymentAnomalyAlertStateProcessor(PaymentAnomalyAlertStatePort states, NotificationBatchInsertPort notifications,
                                            SnowflakeIdGenerator ids, Clock clock, @Qualifier("paymentAnomalyTicker") LongSupplier ticker) {
        this.states = states;
        this.notifications = notifications;
        this.ids = ids;
        this.clock = clock;
        this.ticker = ticker;
    }

    @Transactional(timeout = 15)
    public List<Notification> accept(PaymentAnomalyObservation sample, List<Long> recipients, PaymentAnomalyPolicySettings settings,
                                     PaymentAnomalyObservationTiming timing) {
        Objects.requireNonNull(timing, "timing");
        if (recipients == null || recipients.isEmpty() || recipients.size() > 1000) {
            throw new IllegalArgumentException("Invalid anomaly recipients");
        }
        var distinct = new HashSet<Long>();
        for (var recipient : recipients) {
            if (recipient == null || recipient <= 0 || !distinct.add(recipient)) {
                throw new IllegalArgumentException("Invalid anomaly recipients");
            }
        }
        var input = List.copyOf(recipients);
        var previous = states.findLocked().orElse(null);
        var now = clock.instant();
        // nanoTime의 시작 부호는 자유이며 정상적인 overflow wrap을 포함한 차이로 기간을 판단한다.
        long elapsed = ticker.getAsLong() - timing.startedNanos();
        if (timing.afterHttp().isBefore(timing.beforeHttp()) || now.isBefore(timing.afterHttp())
            || elapsed < 0 || elapsed > settings.maxObservationAge().toNanos()) {
            return List.of();
        }
        var decision = PaymentAnomalyPolicy.accept(previous, sample, now, settings);
        if (decision.state() == previous) {
            return List.of();
        }
        if (previous == null) {
            states.insertInitial(decision.state());
        } else {
            states.updateLocked(decision.state());
        }
        var pending = new ArrayList<Notification>(decision.notices().size() * input.size());
        for (var family : decision.notices().keySet().stream().sorted(Comparator.comparing(Enum::name)).toList()) {
            var notice = decision.notices().get(family);
            for (long recipient : input) {
                long id = ids.generateId();
                pending.add(Notification.create(id, new NotificationRequest(NotificationKind.ANOMALY, notice.severity(), title(family, notice.signal()),
                    "/admin/payments", recipient, "PAYMENT_ANOMALY_ALERT", id)));
            }
        }
        return pending.isEmpty() ? List.of() : List.copyOf(notifications.insertAll(pending));
    }

    private static String title(PaymentAnomalyPolicy.Family family, PaymentAnomalyPolicy.Signal signal) {
        String subject = switch (family) {
            case UNKNOWN -> "결제 결과 미확정";
            case IN_PROGRESS -> "결제 승인 처리 대기";
            case RETURNED_READY -> "결제 준비 복귀";
            case LATE_APPROVED -> "결제 늦은 승인";
            case DONE_ORDER_UNSYNCED -> "결제 완료 주문 미동기화";
        };
        return subject + switch (signal) {
            case OPENED -> " 후보가 감지되었습니다. 담당자는 점검해 주세요.";
            case ESCALATED -> " 후보의 수동 확인이 필요합니다. 담당자는 즉시 점검해 주세요.";
            case REPEATED -> " 후보가 지속되고 있습니다. 담당자는 점검해 주세요.";
            case CANDIDATES_DISAPPEARED -> " 후보가 관측되지 않습니다. 업무 처리 결과는 별도로 확인해 주세요.";
        };
    }
}
