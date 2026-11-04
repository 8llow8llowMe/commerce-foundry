package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.command.PaymentApprovalSummaryCommand;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.info.PaymentApprovalSummaryInfo;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentApprovalSummaryQueryPort;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentApprovalSummaryProcessor {
    private static final Duration MAX_PERIOD = Duration.ofDays(31);
    private final PaymentApprovalSummaryQueryPort queryPort;

    public PaymentApprovalSummaryInfo summarize(PaymentApprovalSummaryCommand command) {
        if (command.from() == null || command.to() == null || !command.from().isBefore(command.to())
            || Duration.between(command.from(), command.to()).compareTo(MAX_PERIOD) > 0) {
            throw new PaymentException(PaymentErrorCode.APPROVAL_SUMMARY_PERIOD_INVALID);
        }
        var groups = queryPort.summarize(command.from(), command.to()).stream()
            .map(row -> new PaymentApprovalSummaryInfo.Group(row.method(), row.approvalCount(), row.grossApprovedAmount())).toList();
        return new PaymentApprovalSummaryInfo(command.from(), command.to(), groups);
    }
}
