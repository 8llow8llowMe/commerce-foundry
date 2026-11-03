package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 쓰기 메서드는 호출자 트랜잭션에 참여하며 CAS 실패는 예외다. */
public interface PaymentWebhookInboxPort {
    PaymentWebhookInbox insert(PaymentWebhookInbox row);
    Optional<PaymentWebhookInbox> findByFingerprint(String fingerprint);
    Optional<PaymentWebhookInbox> findById(long id);
    List<PaymentWebhookInbox> findDue(Instant now, int size);
    PaymentWebhookInbox claim(PaymentWebhookInbox expected, PaymentWebhookInbox claimed, Instant now);
    void finish(PaymentWebhookInbox claimed, PaymentWebhookInbox result, Instant now);
}
