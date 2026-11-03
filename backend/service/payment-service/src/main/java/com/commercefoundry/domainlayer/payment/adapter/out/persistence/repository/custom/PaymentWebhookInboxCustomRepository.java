package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox;
import java.time.Instant;

public interface PaymentWebhookInboxCustomRepository {
    PaymentWebhookInbox claim(PaymentWebhookInbox expected, PaymentWebhookInbox claimed, Instant now);
    void finish(PaymentWebhookInbox claimed, PaymentWebhookInbox result, Instant now);
}
