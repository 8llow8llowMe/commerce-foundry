package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom.PaymentRecoveryCustomRepository;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRecoveryQueryPort;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 복구 후보 질의의 얇은 어댑터. 동적 질의는 custom repository가 소유한다. */
@Repository
@RequiredArgsConstructor
public class PaymentRecoveryQueryAdapter implements PaymentRecoveryQueryPort {
    private final PaymentRecoveryCustomRepository repository;

    @Override
    public List<Candidate> findCandidates(PaymentStatus status, Instant cutoff, Candidate after, int limit) {
        return repository.findCandidates(status, cutoff, after, limit);
    }
}
