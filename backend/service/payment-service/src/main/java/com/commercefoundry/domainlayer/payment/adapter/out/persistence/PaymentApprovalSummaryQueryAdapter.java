package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom.PaymentApprovalSummaryCustomRepository;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentApprovalSummaryQueryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentApprovalSummaryQueryResult;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentApprovalSummaryQueryAdapter implements PaymentApprovalSummaryQueryPort {
    private final PaymentApprovalSummaryCustomRepository repository;

    @Override
    public List<PaymentApprovalSummaryQueryResult> summarize(Instant from, Instant to) {
        try {
            return repository.summarize(from, to);
        } catch (ArithmeticException exception) {
            throw new PaymentException(PaymentErrorCode.APPROVAL_SUMMARY_OVERFLOW, exception);
        }
    }
}
