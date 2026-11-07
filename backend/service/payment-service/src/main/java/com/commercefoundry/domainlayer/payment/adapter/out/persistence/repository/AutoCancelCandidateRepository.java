package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import jakarta.persistence.QueryHint;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;

public interface AutoCancelCandidateRepository extends Repository<PaymentEntity, Long> {
    @Query("select coalesce(max(p.id),0) from PaymentEntity p")
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    long upperId();

    @Query("select p.id from PaymentEntity p where p.status in :statuses and p.orderSynced = false and p.orderSyncFailureCode is not null"
        + " and p.id > :afterId and p.id <= :upperId and not exists"
        + " (select a.paymentId from PaymentAutoCancelEntity a where a.paymentId = p.id) order by p.id")
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    List<Long> rejected(Collection<PaymentStatus> statuses, long afterId, long upperId, Pageable page);

    @Query("select p.id from PaymentEntity p where p.status = :status and p.id > :afterId and p.id <= :upperId and not exists"
        + " (select a.paymentId from PaymentAutoCancelEntity a where a.paymentId = p.id) order by p.id")
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    List<Long> lateApproved(PaymentStatus status, long afterId, long upperId, Pageable page);
}
