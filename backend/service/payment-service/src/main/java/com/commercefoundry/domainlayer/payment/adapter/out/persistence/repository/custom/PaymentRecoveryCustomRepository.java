package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRecoveryQueryPort.Candidate;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import java.time.Instant;
import java.util.List;

public interface PaymentRecoveryCustomRepository {
    List<Candidate> findCandidates(PaymentStatus status, Instant cutoff, Candidate after, int limit);
}
