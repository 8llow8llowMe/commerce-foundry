package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.common.exception.RedactedThrowable;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderSummaryItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderDetailResponse;
import com.commercefoundry.domainlayer.order.adapter.in.web.presenter.OrderHistoryWebPresenter;
import com.commercefoundry.domainlayer.order.application.exception.ClaimRequestKeyConflictException;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.info.OrderMemberSummaryInfo;
import com.commercefoundry.domainlayer.order.application.port.in.OrderHistoryWebUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationPipelineProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderHistoryProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.persistence.dto.SliceResponse;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 주문 내역 · 상세 · 취소 (#16). 트랜잭션은 Processor 가 건다 — 이 Facade 는 트랜잭션 밖이라 버전 충돌 뒤 새 트랜잭션으로 다시 읽을 수 있고,
 * 결제 후 취소의 환불 · 재고 복원(원격 호출)을 DB 커넥션 없이 기다린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class OrderHistoryWebFacade implements OrderHistoryWebUseCase {

    /** 결제 후 취소 경로로 갈 상태 — 결제 전 경로가 진 뒤 다시 읽은 상태가 이것이면 결제 후 경로로 넘긴다. */
    private static final Set<OrderStatus> PAID_BEFORE_SHIPMENT = EnumSet.of(OrderStatus.PAID, OrderStatus.MAKING, OrderStatus.PREPARING);

    private final OrderHistoryProcessor orderHistoryProcessor;
    private final OrderCancellationProcessor orderCancellationProcessor;
    private final OrderCancellationPipelineProcessor orderCancellationPipelineProcessor;
    private final OrderHistoryWebPresenter orderHistoryWebPresenter;

    @Override
    public SliceResponse<OrderSummaryItem> getOrders(long memberId, int page, int size) {
        Slice<OrderMemberSummaryInfo> slice = orderHistoryProcessor.findSlice(memberId, page, size);
        // 운영자가 취소를 거절한 결제 후 주문은 취소 버튼을 끈다(#17) — 취소 가능으로 판정된 결제 후 주문만 모아 한 번에 묻는다
        List<Long> paidCancelable = slice.getContent().stream()
            .filter(row -> row.cancelable() && PAID_BEFORE_SHIPMENT.contains(row.status())).map(OrderMemberSummaryInfo::orderId).toList();
        Set<Long> rejected = paidCancelable.isEmpty() ? Set.of() : orderCancellationProcessor.findRejectedOrderIds(paidCancelable);
        return orderHistoryWebPresenter.toSliceResponse(rejected.isEmpty() ? slice : slice.map(row -> rejected.contains(row.orderId()) ? row.notCancelable() : row));
    }

    @Override
    public OrderDetailResponse getOrder(long memberId, long orderId) {
        return toDetail(orderHistoryProcessor.get(memberId, orderId));
    }

    /**
     * 결제 대기 · 취소 완료는 결제 전 취소 경로(PR-1) 그대로, 그 밖은 결제 후 취소다. 결제 후 취소는 접수(tx1)를 커밋한 뒤 트랜잭션 밖에서 환불 · 재고 복원을
     * 이어 간다 — 원격 결과를 기다리는 동안 DB 커넥션을 쥐지 않고, 원격이 성공한 뒤의 롤백으로 "PG 는 취소했는데 DB 는 모르는" 상태를 만들지 않으려고
     * 트랜잭션을 단계별 Processor 로 좁혔다. 접수 뒤의 실패는 클레임이 남아 재시도 스케줄러가 잇고, 응답은 그 시점의 주문 상세다.
     */
    @Override
    public OrderDetailResponse cancel(long memberId, long orderId, OrderCancelReason reason) {
        Order current = orderHistoryProcessor.get(memberId, orderId);
        if (current.status() == OrderStatus.PENDING_PAYMENT || current.status() == OrderStatus.CANCELED) {
            return toDetail(cancelBeforePayment(memberId, orderId, reason));
        }
        return toDetail(cancelAfterPayment(memberId, orderId, reason));
    }

    /** 결제 전 경로 — 읽은 뒤 결제 완료가 먼저 커밋해 지면, 다시 읽어 결제 후 상태면 결제 후 경로로 넘긴다. */
    private Order cancelBeforePayment(long memberId, long orderId, OrderCancelReason reason) {
        try {
            return orderHistoryProcessor.cancel(memberId, orderId, reason);
        } catch (OptimisticLockingFailureException | OrderException exception) {
            if (exception instanceof OrderException orderException && orderException.getErrorCode() != OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID) {
                throw orderException;
            }
            // 읽은 뒤 다른 곳이 먼저 바꿨다 — 같은 취소(더블 클릭)가 먼저 끝났으면 성공, 결제 완료면 결제 후 경로, 만료 등이면 그 상태로 거절한다
            Order current = orderHistoryProcessor.get(memberId, orderId);
            if (current.status() == OrderStatus.CANCELED) {
                return current;
            }
            if (PAID_BEFORE_SHIPMENT.contains(current.status())) {
                return cancelAfterPayment(memberId, orderId, reason);
            }
            log.warn("customer cancel lost to a concurrent change. orderId={}, status={}", orderId, current.status());
            throw new OrderException(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID, current.status().getDisplayName());
        }
    }

    private Order cancelAfterPayment(long memberId, long orderId, OrderCancelReason reason) {
        OrderCancellationProcessor.Requested requested;
        try {
            requested = orderCancellationProcessor.request(memberId, orderId, reason);
        } catch (ConcurrencyFailureException | ClaimRequestKeyConflictException | OptimisticLockException | PessimisticLockException
                 | LockTimeoutException exception) {
            // 잠금 · 버전 · 결정적 키에서 졌다 — 같은 취소(더블 클릭)가 먼저 접수했으면 재생, 아니면 다시 판정할 수 없다(다시 조회 뒤 재시도)
            return orderCancellationProcessor.findReplay(memberId, orderId).orElseThrow(() -> {
                log.warn("paid cancel lost to a concurrent change. orderId={}, error={}", orderId, exception.getClass().getSimpleName());
                return new OrderException(OrderErrorCode.ORDER_CONCURRENTLY_CHANGED);
            });
        }
        if (requested.created()) {
            try {
                orderCancellationPipelineProcessor.progress(requested.claim());
            } catch (RuntimeException exception) {
                // 접수는 커밋됐다 — 클레임의 다음 시각에 재시도 스케줄러가 같은 클레임으로 잇는다
                log.error("paid cancel pipeline interrupted after request. orderId={}, claimId={}, error={}", orderId, requested.claim().id(),
                    exception.getClass().getSimpleName(), RedactedThrowable.of(exception));
            }
        }
        return orderHistoryProcessor.get(memberId, orderId);
    }

    @Override
    public OrderDetailResponse confirmPurchase(long memberId, long orderId) {
        Order confirmed;
        try {
            confirmed = orderHistoryProcessor.confirmPurchase(memberId, orderId);
        } catch (OptimisticLockingFailureException exception) {
            Order current = orderHistoryProcessor.get(memberId, orderId);
            if (current.status() != OrderStatus.PURCHASE_CONFIRMED) {
                throw new OrderException(OrderErrorCode.ORDER_CONCURRENTLY_CHANGED);
            }
            confirmed = current;
        }
        return toDetail(confirmed);
    }

    /**
     * 상세 응답 — {@code cancelable} 은 Processor 가 판정한 값을 함께 넘긴다(결제 후 취소 게이트 · 제작 중 설정). 운영자가 결제 후 취소를 거절한 주문은
     * false 다(#17 — 다시 취소하면 {@code ORDER_037}).
     */
    private OrderDetailResponse toDetail(Order order) {
        boolean cancelable = orderHistoryProcessor.cancelable(order.status());
        if (cancelable && PAID_BEFORE_SHIPMENT.contains(order.status())) {
            cancelable = orderCancellationProcessor.findRejectedOrderIds(List.of(order.id())).isEmpty();
        }
        return orderHistoryWebPresenter.toDetailResponse(order, cancelable);
    }
}
