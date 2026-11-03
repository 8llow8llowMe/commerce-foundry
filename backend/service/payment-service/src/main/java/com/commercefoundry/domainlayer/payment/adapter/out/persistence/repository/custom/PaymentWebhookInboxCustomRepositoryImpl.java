package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.QPaymentWebhookInboxEntity.paymentWebhookInboxEntity;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentWebhookConflictException;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox.State;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@RequiredArgsConstructor
@Repository
public class PaymentWebhookInboxCustomRepositoryImpl implements PaymentWebhookInboxCustomRepository {
    private final JPAQueryFactory queries;
    @Override public PaymentWebhookInbox claim(PaymentWebhookInbox expected, PaymentWebhookInbox claimed, Instant now) {
        var i = paymentWebhookInboxEntity;
        long changed = queries.update(i).set(i.state, claimed.state()).set(i.attempts, claimed.attempts())
            .set(i.availableAt, claimed.availableAt()).set(i.claimToken, claimed.claimToken()).set(i.updatedAt, claimed.updatedAt())
            .set(i.version, i.version.add(1))
            .where(i.id.eq(expected.id()), i.version.eq(expected.version()), i.state.eq(expected.state()), i.availableAt.loe(now)).execute();
        if (changed != 1) { throw new PaymentWebhookConflictException(); }
        return new PaymentWebhookInbox(claimed.id(), claimed.paymentId(), claimed.fingerprint(), claimed.state(), claimed.attempts(),
            claimed.availableAt(), claimed.claimToken(), claimed.reason(), claimed.receivedAt(), claimed.updatedAt(), expected.version() + 1);
    }
    @Override public void finish(PaymentWebhookInbox claimed, PaymentWebhookInbox result, Instant now) {
        var i = paymentWebhookInboxEntity;
        long changed = queries.update(i).set(i.state, result.state()).set(i.reason, result.reason()).set(i.availableAt, result.availableAt())
            .setNull(i.claimToken).set(i.updatedAt, result.updatedAt()).set(i.version, i.version.add(1))
            .where(i.id.eq(claimed.id()), i.version.eq(claimed.version()), i.state.eq(State.PROCESSING),
                i.claimToken.eq(claimed.claimToken()), i.availableAt.gt(now)).execute();
        if (changed != 1) { throw new PaymentWebhookConflictException(); }
    }
}
