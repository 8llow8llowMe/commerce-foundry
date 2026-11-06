package com.commercefoundry.domainlayer.payment.application.service;

import static com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException.Reason.*;

import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException;
import com.commercefoundry.domainlayer.payment.application.model.OrderPaymentReplyOutcome;
import com.commercefoundry.domainlayer.payment.application.port.in.OrderPaymentReplyEventUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.OrderPaymentReplyProcessor;
import com.commercefoundry.persistence.util.ConstraintViolations;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 원격 조회를 DB 트랜잭션 밖에 두고 기록 프록시의 커밋 실패까지 안전한 내부 분류로 변환한다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class OrderPaymentReplyEventFacade implements OrderPaymentReplyEventUseCase {
    private final OrderPaymentReplyProcessor processor;

    @Override
    public OrderPaymentReplyOutcome handle(OrderPaymentReplyCommand command) {
        try {
            if (processor.isProcessed(command.eventId())) {
                return OrderPaymentReplyOutcome.alreadyProcessed();
            }
            return processor.record(command, processor.fetchSource(command));
        } catch (PaymentPersistenceConflictException failure) {
            throw new OrderPaymentReplyException(failure.getKind() == PaymentPersistenceConflictException.Kind.VERSION ? PAYMENT_VERSION : STORAGE_CONTRACT);
        } catch (OptimisticLockingFailureException failure) {
            throw new OrderPaymentReplyException(PAYMENT_VERSION);
        } catch (DataIntegrityViolationException failure) {
            throw new OrderPaymentReplyException(ConstraintViolations.violates(failure, "uk_processed_event_consumer_group_event_id")
                ? INBOX_DUPLICATE : STORAGE_CONTRACT);
        } catch (DataAccessException | TransactionException failure) {
            throw new OrderPaymentReplyException(STORAGE_UNAVAILABLE);
        }
    }
}
