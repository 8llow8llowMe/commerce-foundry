package com.commercefoundry.domainlayer.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.PaymentAnomalyObservationAdapter;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentAutoCancelEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAnomalyObservationProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAbortReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAnomalyObservation;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAutoCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentGateway;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.shared.commerce.CurrencyCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@Transactional
abstract class PaymentAnomalyObservationQueryContract {
    static final Instant NOW = Instant.parse("2026-11-03T01:02:03.123456Z");
    @Autowired PaymentAnomalyObservationAdapter adapter;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    private long sequence;

    @Test
    void emptySnapshotHasSixZeroNullBuckets() {
        var r = adapter.observeAnomalies(NOW);
        assertThat(r.observedAt()).isEqualTo(NOW);
        assertThat(Arrays.asList(r.unknown(), r.manualReviewUnknown(), r.inProgress(), r.returnedReady(), r.lateApproved(), r.doneOrderUnsynced()))
            .allSatisfy(bucket -> { assertThat(bucket.count()).isZero(); assertThat(bucket.oldestAt()).isNull(); });
        assertThat(r.autoCancelManualReview()).isNull();
        assertThat(adapter.observeAutoCancelManualReview(NOW)).isEqualTo(new PaymentAnomalyObservation.Bucket(0, null));
    }

    @Test
    void allLanesSubsetBoundaryMinAndExclusionsUseOneSelect() {
        row(PaymentStatus.UNKNOWN, NOW, PaymentUnknownReason.MANUAL_REVIEW, false, true);
        for (var reason : PaymentUnknownReason.values()) {
            if (reason != PaymentUnknownReason.MANUAL_REVIEW) row(PaymentStatus.UNKNOWN, NOW.minusSeconds(60), reason, false, true);
        }
        for (var status : new PaymentStatus[]{PaymentStatus.IN_PROGRESS, PaymentStatus.READY, PaymentStatus.LATE_APPROVED, PaymentStatus.DONE}) {
            row(status, NOW, null, false, true);
            row(status, NOW.minusSeconds(30), null, false, true);
        }
        row(PaymentStatus.READY, null, null, false, false);
        row(PaymentStatus.DONE, NOW.plusSeconds(10), null, true, true);
        for (var status : new PaymentStatus[]{PaymentStatus.FAILED, PaymentStatus.ABORTED, PaymentStatus.WAITING_FOR_DEPOSIT,
            PaymentStatus.PARTIAL_CANCELED, PaymentStatus.CANCELED}) row(status, NOW.plusSeconds(10), null, false, true);
        entityManager.flush();
        entityManager.clear();
        var statistics = statistics();
        var r = adapter.observeAnomalies(NOW);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(r.unknown().count()).isEqualTo(PaymentUnknownReason.values().length);
        assertThat(r.unknown().oldestAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(r.manualReviewUnknown().count()).isEqualTo(1);
        assertThat(r.manualReviewUnknown().oldestAt()).isEqualTo(NOW);
        assertThat(Arrays.asList(r.inProgress(), r.returnedReady(), r.lateApproved(), r.doneOrderUnsynced())).allSatisfy(bucket -> {
            assertThat(bucket.count()).isEqualTo(2);
            assertThat(bucket.oldestAt()).isEqualTo(NOW.minusSeconds(30));
        });
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"UNKNOWN", "IN_PROGRESS", "READY", "LATE_APPROVED", "DONE"})
    void futureTimestampFailsEvenWhenHiddenByPastMinimum(PaymentStatus status) {
        row(status, NOW.minusSeconds(60), PaymentUnknownReason.MANUAL_REVIEW, false, true);
        row(status, NOW.plusNanos(1_000), PaymentUnknownReason.MANUAL_REVIEW, false, true);
        entityManager.flush();
        assertThatThrownBy(() -> adapter.observeAnomalies(NOW)).isInstanceOf(DataIntegrityViolationException.class).hasNoCause();
    }

    @Test
    void returnedReadyWithKeyButMissingTimestampFails() {
        row(PaymentStatus.READY, NOW.minusSeconds(1), null, false, true);
        row(PaymentStatus.READY, null, null, false, true);
        entityManager.flush();
        assertThatThrownBy(() -> adapter.observeAnomalies(NOW)).isInstanceOf(DataIntegrityViolationException.class).hasNoCause();
    }

    @Test
    void autoCancelManualReviewCountsOnlyManualReviewWithMinimumNextAttemptInOneSelect() {
        autoCancel(PaymentAutoCancel.State.MANUAL_REVIEW, NOW);
        autoCancel(PaymentAutoCancel.State.MANUAL_REVIEW, NOW.minusSeconds(90));
        autoCancel(PaymentAutoCancel.State.MANUAL_REVIEW, NOW.minusSeconds(30));
        autoCancel(PaymentAutoCancel.State.PENDING, NOW.minusSeconds(600));
        autoCancel(PaymentAutoCancel.State.PENDING, NOW.plusSeconds(600));
        autoCancel(PaymentAutoCancel.State.SUCCEEDED, NOW.minusSeconds(900));
        autoCancel(PaymentAutoCancel.State.SUCCEEDED, NOW.plusSeconds(900));
        entityManager.flush();
        entityManager.clear();
        var statistics = statistics();
        var bucket = adapter.observeAutoCancelManualReview(NOW);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(bucket).isEqualTo(new PaymentAnomalyObservation.Bucket(3, NOW.minusSeconds(90)));
        // 기존 여섯 버킷은 자동 취소 행에 영향을 받지 않고 7번째를 채우지 않는다.
        var r = adapter.observeAnomalies(NOW);
        assertThat(Arrays.asList(r.unknown(), r.manualReviewUnknown(), r.inProgress(), r.returnedReady(), r.lateApproved(), r.doneOrderUnsynced()))
            .allSatisfy(lane -> assertThat(lane).isEqualTo(new PaymentAnomalyObservation.Bucket(0, null)));
        assertThat(r.autoCancelManualReview()).isNull();
    }

    @Test
    void autoCancelFutureNextAttemptFailsEvenWhenHiddenByPastMinimum() {
        autoCancel(PaymentAutoCancel.State.MANUAL_REVIEW, NOW.minusSeconds(60));
        autoCancel(PaymentAutoCancel.State.MANUAL_REVIEW, NOW.plusNanos(1_000));
        entityManager.flush();
        assertThatThrownBy(() -> adapter.observeAutoCancelManualReview(NOW)).isInstanceOf(DataIntegrityViolationException.class).hasNoCause();
    }

    @Test
    void processorWithClosedGateSendsNoAutoCancelStatementAndLeavesBucketUnobserved() {
        row(PaymentStatus.IN_PROGRESS, NOW.minusSeconds(5), null, false, true);
        // 질의했다면 미래 값 때문에 실패했을 행이다.
        autoCancel(PaymentAutoCancel.State.MANUAL_REVIEW, NOW.plusSeconds(60));
        entityManager.flush();
        entityManager.clear();
        var statistics = statistics();
        var r = processor(false).observeAnomalies();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(r.observedAt()).isEqualTo(NOW);
        assertThat(r.inProgress()).isEqualTo(new PaymentAnomalyObservation.Bucket(1, NOW.minusSeconds(5)));
        assertThat(r.autoCancelManualReview()).isNull();
    }

    @Test
    void processorWithOpenGateReadsSecondStatementAtSameTruncatedObservedAt() {
        row(PaymentStatus.IN_PROGRESS, NOW, null, false, true);
        autoCancel(PaymentAutoCancel.State.MANUAL_REVIEW, NOW);
        autoCancel(PaymentAutoCancel.State.MANUAL_REVIEW, NOW.minusSeconds(30));
        entityManager.flush();
        entityManager.clear();
        var statistics = statistics();
        var r = processor(true).observeAnomalies();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(r.observedAt()).isEqualTo(NOW);
        assertThat(r.inProgress()).isEqualTo(new PaymentAnomalyObservation.Bucket(1, NOW));
        assertThat(r.autoCancelManualReview()).isEqualTo(new PaymentAnomalyObservation.Bucket(2, NOW.minusSeconds(30)));
    }

    @Test
    void processorWithOpenGateFailsWholeObservationOnFutureAutoCancelRow() {
        autoCancel(PaymentAutoCancel.State.MANUAL_REVIEW, NOW.minusSeconds(30));
        autoCancel(PaymentAutoCancel.State.MANUAL_REVIEW, NOW.plusNanos(1_000));
        entityManager.flush();
        assertThatThrownBy(() -> processor(true).observeAnomalies()).isInstanceOf(DataIntegrityViolationException.class).hasNoCause();
    }

    private PaymentAnomalyObservationProcessor processor(boolean autoCancelEnabled) {
        // 마이크로초 아래를 버리면 NOW 가 되게 시계를 둔다. 경계(NOW) 행은 미래가 아니다.
        var processor = new PaymentAnomalyObservationProcessor(adapter, Clock.fixed(NOW.plusNanos(789), ZoneOffset.UTC));
        ReflectionTestUtils.setField(processor, "autoCancelEnabled", autoCancelEnabled);
        return processor;
    }

    private Statistics statistics() {
        var statistics = entityManagerFactory.unwrap(SessionFactoryImplementor.class).getStatistics();
        statistics.clear();
        return statistics;
    }

    private void autoCancel(PaymentAutoCancel.State state, Instant nextAttemptAt) {
        long id = ++sequence;
        String reason = state == PaymentAutoCancel.State.MANUAL_REVIEW ? PaymentAutoCancel.MANUAL_REASON : null;
        entityManager.persist(PaymentAutoCancelEntity.from(new PaymentAutoCancel(id, null, "sentinel-pg-key-" + id, null, state, 0,
            nextAttemptAt, null, null, null, null, reason)));
    }

    private void row(PaymentStatus status, Instant timestamp, PaymentUnknownReason reason, boolean synced, boolean key) {
        long id = ++sequence;
        boolean approved = status == PaymentStatus.DONE || status == PaymentStatus.LATE_APPROVED
            || status == PaymentStatus.CANCELED || status == PaymentStatus.PARTIAL_CANCELED;
        var builder = PaymentEntity.builder().id(id).orderId(id).memberId(1L).pgOrderId("sentinel-" + id).gateway(PaymentGateway.TOSS)
            .totalAmount(1000).totalCurrency(CurrencyCode.KRW).orderName("sentinel-order").status(status).orderSynced(synced)
            .activeOrderId(status == PaymentStatus.FAILED || status == PaymentStatus.ABORTED || status == PaymentStatus.LATE_APPROVED ? null : id)
            .paymentKey(key ? "sentinel-key-" + id : null).approvalStartedAt(key ? timestamp : null)
            .unknownReason(status == PaymentStatus.UNKNOWN ? reason : null);
        if (approved) builder.method(PaymentMethod.CARD).approvedAt(timestamp).approvalStartedAt(timestamp.minusSeconds(600));
        if (status == PaymentStatus.FAILED || status == PaymentStatus.LATE_APPROVED) builder.failureCode("sentinel-failure");
        if (status == PaymentStatus.ABORTED) builder.abortReason(PaymentAbortReason.ADMIN);
        entityManager.persist(builder.build());
    }
}
