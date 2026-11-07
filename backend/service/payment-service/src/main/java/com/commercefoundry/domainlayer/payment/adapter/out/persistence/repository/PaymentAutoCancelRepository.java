package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentAutoCancelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAutoCancel;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.transaction.annotation.Transactional;

public interface PaymentAutoCancelRepository extends JpaRepository<PaymentAutoCancelEntity, Long> {
    boolean existsByClaimId(Long claimId);
    @Transactional(readOnly = true, timeout = 2)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    long countByState(PaymentAutoCancel.State state);

    @Query("select coalesce(max(a.paymentId),0) from PaymentAutoCancelEntity a")
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    long upperId();

    @Query("select a.paymentId from PaymentAutoCancelEntity a where a.state = :state and a.nextAttemptAt <= :cutoff"
        + " and (a.leaseUntil is null or a.leaseUntil <= :cutoff) and a.paymentId > :afterId and a.paymentId <= :upperId order by a.paymentId")
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    List<Long> dueIds(PaymentAutoCancel.State state, Instant cutoff, long afterId, long upperId, Pageable page);
}
