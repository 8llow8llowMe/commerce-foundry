package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderSummaryItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderDetailResponse;
import com.commercefoundry.domainlayer.order.adapter.in.web.presenter.OrderHistoryWebPresenter;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.in.OrderHistoryWebUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderHistoryProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.persistence.dto.SliceResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 주문 내역 · 상세 · 결제 전 취소 (#16 PR-1). 트랜잭션은 Processor 가 건다 — 이 Facade 는 트랜잭션 밖이라 버전 충돌 뒤 새 트랜잭션으로 다시 읽을 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class OrderHistoryWebFacade implements OrderHistoryWebUseCase {

    private final OrderHistoryProcessor orderHistoryProcessor;
    private final OrderHistoryWebPresenter orderHistoryWebPresenter;

    @Override
    public SliceResponse<OrderSummaryItem> getOrders(long memberId, int page, int size) {
        return orderHistoryWebPresenter.toSliceResponse(orderHistoryProcessor.findSlice(memberId, page, size));
    }

    @Override
    public OrderDetailResponse getOrder(long memberId, long orderId) {
        return orderHistoryWebPresenter.toDetailResponse(orderHistoryProcessor.get(memberId, orderId));
    }

    @Override
    public OrderDetailResponse cancel(long memberId, long orderId, OrderCancelReason reason) {
        Order canceled;
        try {
            canceled = orderHistoryProcessor.cancel(memberId, orderId, reason);
        } catch (OptimisticLockingFailureException exception) {
            // 읽은 뒤 다른 곳이 먼저 바꿨다 — 같은 취소(더블 클릭)가 먼저 끝났으면 성공, 결제 완료 · 만료면 그 상태로 거절한다
            Order current = orderHistoryProcessor.get(memberId, orderId);
            if (current.status() != OrderStatus.CANCELED) {
                log.warn("customer cancel lost to a concurrent change. orderId={}, status={}", orderId, current.status());
                throw new OrderException(OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID, current.status().getDisplayName());
            }
            canceled = current;
        }
        return orderHistoryWebPresenter.toDetailResponse(canceled);
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
        return orderHistoryWebPresenter.toDetailResponse(confirmed);
    }
}
