package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentWebhookInboxEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentWebhookInboxRepository;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentWebhookConflictException;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentWebhookInboxPort;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox.State;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentWebhookInboxAdapter implements PaymentWebhookInboxPort {
    private final PaymentWebhookInboxRepository repository;

    @Override public PaymentWebhookInbox insert(PaymentWebhookInbox row) {
        try { return repository.saveAndFlush(PaymentWebhookInboxEntity.from(row)).toDomain(); }
        catch (DataIntegrityViolationException conflict) {
            // 중복 fingerprint 조회는 반드시 이 트랜잭션이 끝난 뒤 수행한다. DB 오류 원문을 전달하지 않는다.
            throw new PaymentWebhookConflictException();
        }
    }
    @Override public Optional<PaymentWebhookInbox> findByFingerprint(String fingerprint) {
        return repository.findByFingerprint(fingerprint).map(PaymentWebhookInboxEntity::toDomain);
    }
    @Override public Optional<PaymentWebhookInbox> findById(long id) { return repository.findById(id).map(PaymentWebhookInboxEntity::toDomain); }
    @Override public List<PaymentWebhookInbox> findDue(Instant now, int size) {
        return repository.findDue(List.of(State.PENDING, State.RETRY, State.PROCESSING), now, PageRequest.of(0, size)).stream()
            .map(PaymentWebhookInboxEntity::toDomain).toList();
    }
    @Override public PaymentWebhookInbox claim(PaymentWebhookInbox expected, PaymentWebhookInbox claimed, Instant now) {
        return repository.claim(expected, claimed, now);
    }
    @Override public void finish(PaymentWebhookInbox claimed, PaymentWebhookInbox result, Instant now) { repository.finish(claimed, result, now); }
}
