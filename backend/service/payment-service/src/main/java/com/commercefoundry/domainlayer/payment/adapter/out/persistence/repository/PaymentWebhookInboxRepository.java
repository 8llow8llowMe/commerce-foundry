package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentWebhookInboxEntity;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox.State;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom.PaymentWebhookInboxCustomRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PaymentWebhookInboxRepository extends JpaRepository<PaymentWebhookInboxEntity, Long>, PaymentWebhookInboxCustomRepository {
    Optional<PaymentWebhookInboxEntity> findByFingerprint(String fingerprint);
    @Query("select i from PaymentWebhookInboxEntity i where i.state in :states and i.availableAt <= :now order by i.availableAt, i.id")
    List<PaymentWebhookInboxEntity> findDue(Collection<State> states, Instant now, Pageable pageable);
}
