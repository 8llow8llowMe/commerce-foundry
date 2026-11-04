package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.command.PaymentApprovalCommand;
import com.commercefoundry.domainlayer.order.application.exception.PaymentQueryException;
import com.commercefoundry.domainlayer.order.application.model.VerifiedPayment;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentQueryResult;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentApprovalProcessor {

    private final PaymentQueryPort paymentQueryPort;

    /** 원격 I/O는 DB 트랜잭션 밖에서 한다. 원인 응답/메시지를 Kafka 예외 사슬에 싣지 않는다. */
    @Transactional(propagation = Propagation.NEVER)
    public VerifiedPayment verify(PaymentApprovalCommand command) {
        PaymentQueryResult payment;
        try {
            payment = paymentQueryPort.findPayment(command.paymentId())
                .orElseThrow(() -> new NonRetryableEventException("payment not found"));
        } catch (PaymentQueryException exception) {
            if (exception.isRetryable()) {
                throw new PaymentQueryException(exception.getFailure());
            }
            throw new NonRetryableEventException("payment query contract rejected");
        }
        boolean approvalRequired = payment.isDone() || "CANCELED".equals(payment.statusCode()) || "PARTIAL_CANCELED".equals(payment.statusCode());
        if (payment.paymentId() != command.paymentId() || payment.orderId() != command.orderId() || payment.memberId() != command.memberId()
            || !payment.amount().equals(command.amount())
            || (approvalRequired && (payment.approvedAt() == null || payment.method() == null))
            || ((approvalRequired || payment.approvedAt() != null) && !Objects.equals(micros(payment.approvedAt()), micros(command.approvedAt())))
            || ((approvalRequired || payment.method() != null) && !Objects.equals(payment.method(), command.method()))) {
            throw new NonRetryableEventException("payment notification mismatch");
        }
        return new VerifiedPayment(payment.paymentId(), payment.orderId(), payment.memberId(), payment.amount(), payment.approvedAt(),
            payment.statusCode());
    }

    private static Instant micros(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }
}
