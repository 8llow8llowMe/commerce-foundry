package com.commercefoundry.domainlayer.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.PaymentAnomalyObservationAdapter;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentRepository;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAbortReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentGateway;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.shared.commerce.CurrencyCode;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
abstract class PaymentOrderSyncQueryContract {
    static final Instant NOW = Instant.parse("2026-11-04T01:02:03.123456Z");
    static final List<PaymentStatus> HOLDING = List.of(PaymentStatus.DONE, PaymentStatus.PARTIAL_CANCELED);
    static final String PENDING_SQL = " from payment.payment where status in ('DONE','PARTIAL_CANCELED')"
        + " and order_synced=false and order_sync_failure_code is null and approved_at < ?";
    static final String REJECTED_SQL = " from payment.payment where status in ('DONE','PARTIAL_CANCELED')"
        + " and order_synced=false and order_sync_failure_code is not null";
    @Autowired PaymentRepository repository;
    @Autowired PaymentAnomalyObservationAdapter anomaly;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    private long sequence;

    @Test
    void emptyMinIsNullAndCountsAreZero() {
        assertThat(repository.countAwaitingOrderReply(HOLDING, NOW)).isZero();
        assertThat(repository.findOldestAwaitingOrderReplyApprovedAt(HOLDING, NOW)).isNull();
        assertThat(repository.countOrderRejected(HOLDING)).isZero();
    }

    @Test
    void statusesSyncRejectionAndMicrosecondBoundariesMatchDirectSql() {
        Instant cutoff = NOW.minusSeconds(300);
        for (PaymentStatus status : PaymentStatus.values()) {
            row(status, cutoff.minusSeconds(600), false, null);
            if (status == PaymentStatus.DONE || status == PaymentStatus.PARTIAL_CANCELED || status == PaymentStatus.CANCELED) {
                row(status, cutoff.minusSeconds(900), true, null);
                row(status, NOW.plusSeconds(60), false, "ORDER_CANCELED");
                row(status, cutoff.minusSeconds(1_200), false, "ORDER_CANCELED");
            }
        }
        for (PaymentStatus status : HOLDING) {
            row(status, cutoff.minusNanos(1_000), false, null);
            row(status, cutoff, false, null);
            row(status, cutoff.plusNanos(1_000), false, null);
            row(status, NOW.plusNanos(1_000), false, null);
        }
        entityManager.flush();
        entityManager.clear();
        assertThat(repository.countAwaitingOrderReply(HOLDING, cutoff)).isEqualTo(4);
        assertThat(repository.countOrderRejected(HOLDING)).isEqualTo(4);
        assertDirectSql(cutoff);
        assertDirectSql(NOW); // pending-after=0도 strict 경계다.
    }

    @Test
    void oldestRemovalChangesMinimumAndLastRemovalRestoresEmpty() {
        PaymentEntity oldest = row(PaymentStatus.DONE, NOW.minusSeconds(900), false, null);
        PaymentEntity next = row(PaymentStatus.PARTIAL_CANCELED, NOW.minusSeconds(600), false, null);
        entityManager.flush();
        assertThat(repository.findOldestAwaitingOrderReplyApprovedAt(HOLDING, NOW.minusSeconds(300))).isEqualTo(NOW.minusSeconds(900));
        entityManager.remove(oldest);
        entityManager.flush();
        assertThat(repository.findOldestAwaitingOrderReplyApprovedAt(HOLDING, NOW.minusSeconds(300))).isEqualTo(NOW.minusSeconds(600));
        entityManager.remove(next);
        entityManager.flush();
        assertThat(repository.findOldestAwaitingOrderReplyApprovedAt(HOLDING, NOW)).isNull();
    }

    @Test
    void zeroThresholdExcludesEqualAndFutureAndIncludesOneMicrosecondOld() {
        row(PaymentStatus.DONE, NOW.minusNanos(1_000), false, null);
        row(PaymentStatus.DONE, NOW, false, null);
        row(PaymentStatus.DONE, NOW.plusNanos(1_000), false, null);
        entityManager.flush();
        assertThat(repository.countAwaitingOrderReply(HOLDING, NOW)).isEqualTo(1);
        assertDirectSql(NOW);
    }

    @Test
    void a4aStillIncludesRecentAndRejectedDoneButNotPartialCanceled() {
        row(PaymentStatus.DONE, NOW.minusSeconds(900), false, "ORDER_CANCELED");
        row(PaymentStatus.DONE, NOW, false, null);
        row(PaymentStatus.PARTIAL_CANCELED, NOW.minusSeconds(1_200), false, null);
        entityManager.flush();
        var bucket = anomaly.observeAnomalies(NOW).doneOrderUnsynced();
        assertThat(bucket.count()).isEqualTo(2);
        assertThat(bucket.oldestAt()).isEqualTo(NOW.minusSeconds(900));
        assertThat(repository.countAwaitingOrderReply(HOLDING, NOW.minusSeconds(300))).isEqualTo(1);
        assertThat(repository.countOrderRejected(HOLDING)).isEqualTo(1);
    }

    private void assertDirectSql(Instant cutoff) {
        Timestamp before = Timestamp.from(cutoff);
        assertThat(repository.countAwaitingOrderReply(HOLDING, cutoff))
            .isEqualTo(jdbc.queryForObject("select count(*)" + PENDING_SQL, Long.class, before));
        Timestamp oldest = jdbc.queryForObject("select min(approved_at)" + PENDING_SQL, Timestamp.class, before);
        assertThat(repository.findOldestAwaitingOrderReplyApprovedAt(HOLDING, cutoff)).isEqualTo(oldest == null ? null : oldest.toInstant());
        assertThat(repository.countOrderRejected(HOLDING)).isEqualTo(jdbc.queryForObject("select count(*)" + REJECTED_SQL, Long.class));
    }

    private PaymentEntity row(PaymentStatus status, Instant approvedAt, boolean synced, String rejection) {
        long id = ++sequence;
        boolean approved = List.of(PaymentStatus.DONE, PaymentStatus.PARTIAL_CANCELED, PaymentStatus.CANCELED, PaymentStatus.LATE_APPROVED).contains(status);
        var builder = PaymentEntity.builder().id(id).orderId(id).memberId(1L).pgOrderId("synthetic-order-" + id)
            .paymentKey("synthetic-key-" + id).approvalIdempotencyKey(String.format("00000000-0000-0000-0000-%012d", id))
            .gateway(PaymentGateway.TOSS).totalAmount(1000).totalCurrency(CurrencyCode.KRW).orderName("synthetic")
            .status(status).orderSynced(synced).orderSyncFailureCode(rejection).approvalStartedAt(approvedAt.minusSeconds(60))
            .activeOrderId(List.of(PaymentStatus.FAILED, PaymentStatus.ABORTED, PaymentStatus.LATE_APPROVED).contains(status) ? null : id);
        if (approved) builder.approvedAt(approvedAt).method(PaymentMethod.CARD);
        if (status == PaymentStatus.UNKNOWN) builder.unknownReason(PaymentUnknownReason.TIMEOUT);
        if (status == PaymentStatus.FAILED || status == PaymentStatus.LATE_APPROVED) builder.failureCode("SYNTHETIC_FAILURE");
        if (status == PaymentStatus.ABORTED) builder.abortReason(PaymentAbortReason.ADMIN);
        PaymentEntity entity = builder.build();
        entityManager.persist(entity);
        return entity;
    }
}
