package com.commercefoundry.domainlayer.payment.application.service;

import static com.commercefoundry.domainlayer.payment.application.service.OrderPaymentReplyObservation.Closure.*;

import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException;
import com.commercefoundry.domainlayer.payment.application.model.OrderPaymentReplyOutcome;
import com.commercefoundry.domainlayer.payment.application.port.in.OrderPaymentReplyEventUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.OrderPaymentReplyProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 원격 조회와 커밋 후 훅은 TX 밖이다. 커밋 실패까지 분류하고 실패한 TX에서는 재조회하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class OrderPaymentReplyEventFacade implements OrderPaymentReplyEventUseCase {
    private final OrderPaymentReplyProcessor processor;
    private final OrderPaymentReplyObservation observation;

    @Override
    public OrderPaymentReplyOutcome handle(OrderPaymentReplyCommand command) {
        OrderPaymentReplyOutcome outcome;
        try {
            if (command == null) {
                throw new OrderPaymentReplyException(OrderPaymentReplyException.Reason.INVALID_COMMAND);
            }
            outcome = processor.isProcessed(command.eventId()) ? OrderPaymentReplyOutcome.alreadyProcessed()
                : processor.record(command, processor.fetchSource(command));
        } catch (RuntimeException failure) {
            var reason = processor.classifyFailure(failure);
            observe(() -> observation.recordFailure(reason));
            throw new OrderPaymentReplyException(reason);
        }
        // record의 Spring 프록시가 커밋을 마친 뒤에만 도달한다. 빠른 훅은 실행 보장이 없다(#591).
        if (outcome.cancelCandidate()) {
            try {
                processor.afterRejection(command.paymentId());
                observe(() -> observation.record(HOOK_DEFERRED));
            } catch (RuntimeException failure) {
                observe(() -> observation.record(HOOK_FAILED));
            }
        } else {
            observe(() -> observation.record(outcome.duplicate() ? DUPLICATE : outcome.rejectionRecorded() ? REJECTION_CLOSED : NO_CANDIDATE));
        }
        return outcome;
    }

    private void observe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 관측 실패로 이미 커밋한 기록이나 훅 시도를 재실행하지 않는다.
        }
    }
}
