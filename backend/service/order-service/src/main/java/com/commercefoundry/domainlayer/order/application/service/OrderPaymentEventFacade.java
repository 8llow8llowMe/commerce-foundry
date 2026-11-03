package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.domainlayer.order.application.command.PaymentApprovalCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.OrderPaymentSaveException;
import com.commercefoundry.domainlayer.order.application.model.PaymentApprovalTarget;
import com.commercefoundry.domainlayer.order.application.model.StockConfirmation;
import com.commercefoundry.domainlayer.order.application.model.StockRestorationTarget;
import com.commercefoundry.domainlayer.order.application.model.VerifiedPayment;
import com.commercefoundry.domainlayer.order.application.port.in.OrderPaymentEventUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderStockProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.PaymentApprovalProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment.Result;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;
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

    /**
     * 원격 호출 동안 DB 커넥션을 쥐지 않는다. 충돌한 트랜잭션을 재사용하지 않고 Processor를 다시 호출한다.
     *
     * <p>확정 뒤 취소가 이긴 경합(#308 PR-C1, {@code order-flow-guide.md} §3-6)은 ① 거절 기록(확정 선점 · 복원 클레임) 커밋 → ② tx 밖 재고 복원 →
     * ③ 복원 시각 · inbox · 거절 회신 커밋 순서다. ② · ③ 이 실패하면 재전달이 ① 의 기록으로 같은 클레임 아이디부터 다시 한다.
     */
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void approve(PaymentApprovalCommand command) {
        if (orderPaymentProcessor.isProcessed(command.eventId())) {
            return;
        }
        VerifiedPayment payment = paymentApprovalProcessor.verify(command);
        StockConfirmation confirmation = null;
        boolean probed = false;
        Optional<StockRestorationTarget> restoration = Optional.empty();
        boolean completed = false;
        for (int attempt = 0; attempt < MAX_CONFLICT_ATTEMPTS && !completed; attempt++) {
            PaymentApprovalTarget target = orderPaymentProcessor.inspect(payment, command.eventId());
            if (!target.processed() && confirmation == null) {
                if (target.rejection() == null) {
                    Order order = target.order();
                    confirmation = stockCall(() -> orderStockProcessor.confirmOrReReserve(order,
                        reReservedId -> orderPaymentProcessor.recordReReservation(payment.paymentId(), order.id(), reReservedId, command.eventId())));
                } else if (target.rejection() == Result.REJECTED_ORDER_CANCELED && !probed) {
                    confirmation = probeConfirmed(payment, target.order());
                    probed = true;
                }
            }
            try {
                restoration = orderPaymentProcessor.complete(command, payment, confirmation);
                completed = true;
            } catch (OrderPaymentSaveException exception) {
                if (exception.getReason() == OrderPaymentSaveException.Reason.REJECTED) {
                    throw exception;
                }
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
                // 다음 호출은 새 트랜잭션이다. inbox/결제/주문을 다시 읽는다.
            } catch (OrderException exception) {
                if (exception.getErrorCode() == OrderErrorCode.STOCK_RESERVATION_INCONSISTENT) {
                    // 확정한 선점을 커밋된 기록이 책임지지 않는다 — 다시 해도 같다. 사람이 복원 · 환불을 확인한다(DLT)
                    throw new NonRetryableEventException("confirmed stock reservation not recorded");
                }
                throw exception;
            }
        }
        if (!completed) {
            throw new IllegalStateException("order payment conflict retry exhausted");
        }
        restoration.ifPresent(target -> restoreAndReply(command, payment, target));
    }

    /**
     * 결제 전 취소로 보이는 주문의 확정 선점 찾기 — 확정 뒤 DB 기록 전에 끊긴 처리가 남긴 확정이 있으면 그 선점을 확정 결과로 삼아 복원으로 넘긴다.
     * 없으면(확정 전 취소) 지금처럼 확정 · 재선점 없이 거절한다.
     */
    private StockConfirmation probeConfirmed(VerifiedPayment payment, Order canceled) {
        OptionalLong confirmed = stockCall(() -> orderStockProcessor.findConfirmedByReleaseProbe(canceled.id(),
            orderPaymentProcessor.confirmationCandidates(payment.paymentId(), canceled)));
        return confirmed.isPresent() ? StockConfirmation.confirmed(confirmed.getAsLong()) : null;
    }

    private void restoreAndReply(PaymentApprovalCommand command, VerifiedPayment payment, StockRestorationTarget target) {
        OrderPayment record = target.orderPayment();
        stockCall(() -> {
            orderStockProcessor.restoreCanceledPayment(record.orderId(), record.paymentId(), record.restorationClaimId(), record.stockReservationId(), target.order().quote());
            return null;
        });
        try {
            orderPaymentProcessor.completeRestoration(command.eventId(), record, payment);
        } catch (DataIntegrityViolationException exception) {
            // 같은 이벤트의 동시 처리가 먼저 마무리했다(inbox 유니크) — 그쪽이 회신을 남겼다.
            if (!orderPaymentProcessor.isProcessed(command.eventId())) {
                throw exception;
            }
        }
    }

    /** 재고 원격 호출의 재시도 분류 — 다시 해도 같은 불일치만 바로 DLT, 그 밖의 업무 오류는 재전달로 다시 한다(503 {@code ORDER_002}). */
    private static <T> T stockCall(Supplier<T> call) {
        try {
            return call.get();
        } catch (OrderException exception) {
            if (exception.getErrorCode() == OrderErrorCode.STOCK_RESERVATION_INCONSISTENT) {
                throw new NonRetryableEventException("stock reservation inconsistent");
            }
            throw new OrderException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
    }
}
