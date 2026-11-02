package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.domainlayer.order.application.command.PaymentApprovalCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.OrderPaymentSaveException;
import com.commercefoundry.domainlayer.order.application.model.PaymentApprovalTarget;
import com.commercefoundry.domainlayer.order.application.model.StockConfirmation;
import com.commercefoundry.domainlayer.order.application.model.VerifiedPayment;
import com.commercefoundry.domainlayer.order.application.port.in.OrderPaymentEventUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderStockProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.PaymentApprovalProcessor;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderPaymentEventFacade implements OrderPaymentEventUseCase {

    private static final int MAX_CONFLICT_ATTEMPTS = 3;
    private final PaymentApprovalProcessor paymentApprovalProcessor;
    private final OrderPaymentProcessor orderPaymentProcessor;
    private final OrderStockProcessor orderStockProcessor;

    /** 원격 호출 동안 DB 커넥션을 쥐지 않는다. 충돌한 트랜잭션을 재사용하지 않고 Processor를 다시 호출한다. */
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void approve(PaymentApprovalCommand command) {
        if (orderPaymentProcessor.isProcessed(command.eventId())) {
            return;
        }
        VerifiedPayment payment = paymentApprovalProcessor.verify(command);
        StockConfirmation confirmation = null;
        for (int attempt = 0; attempt < MAX_CONFLICT_ATTEMPTS; attempt++) {
            PaymentApprovalTarget target = orderPaymentProcessor.inspect(payment, command.eventId());
            if (!target.processed() && target.rejection() == null && confirmation == null) {
                try {
                    confirmation = orderStockProcessor.confirmOrReReserve(target.order());
                } catch (OrderException exception) {
                    if (exception.getErrorCode() == OrderErrorCode.STOCK_RESERVATION_INCONSISTENT) {
                        throw new NonRetryableEventException("stock reservation inconsistent");
                    }
                    throw new OrderException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
                }
            }
            try {
                orderPaymentProcessor.complete(command, payment, confirmation);
                return;
            } catch (OrderPaymentSaveException exception) {
                if (exception.getReason() == OrderPaymentSaveException.Reason.REJECTED) {
                    throw exception;
                }
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
                // 다음 호출은 새 트랜잭션이다. inbox/결제/주문을 다시 읽는다.
            }
        }
        throw new IllegalStateException("order payment conflict retry exhausted");
    }
}
