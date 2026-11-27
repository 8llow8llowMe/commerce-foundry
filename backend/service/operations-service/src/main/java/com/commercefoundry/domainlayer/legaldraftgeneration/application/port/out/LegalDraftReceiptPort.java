package com.commercefoundry.domainlayer.legaldraftgeneration.application.port.out;

import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftReceipt;
import java.time.Instant;
import java.util.Optional;

public interface LegalDraftReceiptPort {
    Optional<LegalDraftReceipt> findByKeyHash(String keyHash);
    Optional<LegalDraftReceipt> findByJobId(long jobId);
    void insert(LegalDraftReceipt receipt);
    void markReviewed(long jobId, Instant reviewedAt);
}
