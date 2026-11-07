package com.commercefoundry.domainlayer.payment.application.port.out;

/** Register observation after commit, only when a pending intent first enters manual review. */
public interface PaymentAutoCancelReviewPort {
    void afterCommit();
}
