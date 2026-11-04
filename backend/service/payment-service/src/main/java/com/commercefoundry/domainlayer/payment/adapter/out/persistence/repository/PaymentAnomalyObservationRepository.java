package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;

/** 상태를 변경하지 않고 한 문장으로 관측한다. 시각 오류는 별도 집계하여 min에 가려지지 않는다. */
public interface PaymentAnomalyObservationRepository extends Repository<PaymentEntity, Long> {
    @Query("""
        select count(case when p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.UNKNOWN
                then 1 else null end) as unknownCount,
            min(case when p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.UNKNOWN
                then p.approvalStartedAt else null end) as unknownOldestAt,
            count(case when p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.UNKNOWN
                and p.unknownReason = com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason.MANUAL_REVIEW
                then 1 else null end) as manualReviewUnknownCount,
            min(case when p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.UNKNOWN
                and p.unknownReason = com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason.MANUAL_REVIEW
                then p.approvalStartedAt else null end) as manualReviewUnknownOldestAt,
            count(case when p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.IN_PROGRESS
                then 1 else null end) as inProgressCount,
            min(case when p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.IN_PROGRESS
                then p.approvalStartedAt else null end) as inProgressOldestAt,
            count(case when p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.READY and p.paymentKey is not null
                then 1 else null end) as returnedReadyCount,
            min(case when p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.READY and p.paymentKey is not null
                then p.approvalStartedAt else null end) as returnedReadyOldestAt,
            count(case when p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.LATE_APPROVED
                then 1 else null end) as lateApprovedCount,
            min(case when p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.LATE_APPROVED
                then p.approvedAt else null end) as lateApprovedOldestAt,
            count(case when p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.DONE and p.orderSynced = false
                then 1 else null end) as doneOrderUnsyncedCount,
            min(case when p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.DONE and p.orderSynced = false
                then p.approvedAt else null end) as doneOrderUnsyncedOldestAt,
            count(case when ((p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.UNKNOWN)
                or (p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.IN_PROGRESS)
                or (p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.READY and p.paymentKey is not null))
                and (p.approvalStartedAt is null or p.approvalStartedAt > :observedAt)
                or ((p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.LATE_APPROVED)
                or (p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.DONE and p.orderSynced = false))
                and (p.approvedAt is null or p.approvedAt > :observedAt)
                then 1 else null end) as invalidTimestampCount
          from PaymentEntity p
         where p.status in (com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.UNKNOWN,
                            com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.IN_PROGRESS,
                            com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.LATE_APPROVED)
            or (p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.READY and p.paymentKey is not null)
            or (p.status = com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus.DONE and p.orderSynced = false)
        """)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    Projection observeAnomalies(Instant observedAt);

    interface Projection {
        long getUnknownCount();
        Instant getUnknownOldestAt();
        long getManualReviewUnknownCount();
        Instant getManualReviewUnknownOldestAt();
        long getInProgressCount();
        Instant getInProgressOldestAt();
        long getReturnedReadyCount();
        Instant getReturnedReadyOldestAt();
        long getLateApprovedCount();
        Instant getLateApprovedOldestAt();
        long getDoneOrderUnsyncedCount();
        Instant getDoneOrderUnsyncedOldestAt();
        long getInvalidTimestampCount();
    }
}
