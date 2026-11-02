package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import java.time.Instant;
import java.util.List;

/** 복합 커서로 제한된 후보만 읽는다. 승인 시작 시각이 없는 과거 행은 의도적으로 제외한다. */
public interface PaymentRecoveryQueryPort {
    List<Candidate> findCandidates(PaymentStatus status, Instant cutoff, Candidate after, int limit);

    record Candidate(long id, Instant approvalStartedAt) {}
}
