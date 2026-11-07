package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.port.in.OrderReconciliationSchedulerUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.OrderPaymentReplyProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.OrderReconciliationScanProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.OrderReconciliationLane;
import com.commercefoundry.global.properties.PaymentOrderReconciliationProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 원천 조회와 정책은 TX 밖이다. 배치 예약과 payment/inbox 기록은 각각 독립된 짧은 TX다. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class OrderReconciliationSchedulerFacade implements OrderReconciliationSchedulerUseCase {
    private final OrderReconciliationScanProcessor scan;
    private final OrderPaymentReplyProcessor replies;
    private final PaymentOrderReconciliationProperties properties;
    private final Clock clock;
    private final Duration pendingAfter;
    private final OrderPaymentReplyObservation failures;
    private final OrderReconciliationObservation observation;

    public OrderReconciliationSchedulerFacade(OrderReconciliationScanProcessor scan, OrderPaymentReplyProcessor replies,
        PaymentOrderReconciliationProperties properties, Clock clock, @Value("${payment.order-result.pending-after:5m}") Duration pendingAfter,
        OrderPaymentReplyObservation failures, OrderReconciliationObservation observation) {
        if (pendingAfter.isNegative()) throw new IllegalArgumentException("payment.order-result.pending-after must be nonnegative");
        this.scan = scan;
        this.replies = replies;
        this.properties = properties;
        this.clock = clock;
        this.pendingAfter = pendingAfter;
        this.failures = failures;
        this.observation = observation;
    }

    @Override
    public void reconcile() {
        if (!properties.enabled()) return;
        var cutoff = clock.instant().minus(pendingAfter);
        for (var lane : OrderReconciliationLane.values()) {
            try {
                for (int page = 0; page < properties.maxPagesPerLane(); page++) {
                    var ids = scan.reserveBatch(lane, cutoff, properties.pageSize());
                    if (ids.isEmpty()) break;
                    // 각 후보는 독립된 복구 작업이다. 원천 단건 API를 재사용하며 새 벌크 API를 열지 않는다(#591).
                    for (long id : ids) {
                        try {
                            if (lane == OrderReconciliationLane.RECONCILE) reconcileOne(id);
                            else replies.afterRejection(id); // inbox/빠른 훅 이력과 무관한 DB 정본 트리거
                        } catch (RuntimeException failure) {
                            report(failure);
                        }
                    }
                }
            } catch (RuntimeException failure) {
                report(failure); // 한 lane의 조회 실패도 다른 lane을 막지 않는다.
            }
        }
    }

    private void reconcileOne(long id) {
        var eventId = syntheticEventId(id);
        if (replies.isProcessed(eventId)) return;
        var source = replies.fetchSource(id);
        var command = new OrderPaymentReplyCommand(eventId,
            source.paid() ? OrderPaymentReplyCommand.Kind.PAID : OrderPaymentReplyCommand.Kind.REJECTED,
            id, source.orderId(), source.rejectionReason());
        var outcome = replies.record(command, source);
        if (outcome.changed()) {
            try {
                observation.changed(OrderReconciliationObservation.Result.from(source));
            } catch (RuntimeException ignored) { /* 관측 실패는 커밋 결과에 영향을 주지 않는다. */ }
        }
        if (outcome.cancelCandidate()) replies.afterRejection(id); // 기존 record 프록시 커밋 뒤 빠른 훅
    }

    public static UUID syntheticEventId(long paymentId) {
        return UUID.nameUUIDFromBytes(("payment-order-reconcile:v1:" + paymentId).getBytes(StandardCharsets.UTF_8));
    }

    private void report(RuntimeException failure) {
        try {
            failures.recordFailure(replies.classifyFailure(failure));
        } catch (RuntimeException ignored) { /* 원문/cause를 로깅하지 않고 다음 후보를 처리한다. */ }
    }
}
