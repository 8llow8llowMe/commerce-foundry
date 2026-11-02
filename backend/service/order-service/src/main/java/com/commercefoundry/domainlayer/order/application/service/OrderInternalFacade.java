package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderFulfillmentInternalResponse;
import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPaymentSummaryInternalResponse;
import com.commercefoundry.domainlayer.order.adapter.in.internal.presenter.OrderFulfillmentInternalPresenter;
import com.commercefoundry.domainlayer.order.adapter.in.internal.presenter.OrderPaymentSummaryInternalPresenter;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.info.OrderFulfillmentInfo;
import com.commercefoundry.domainlayer.order.application.port.in.OrderInternalUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderFulfillmentProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentSummaryProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import java.time.Instant;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서비스 간 주문 조회 · 반영. 결제 요약 조회는 원격 호출이 없는 읽기라 Facade 에 읽기 트랜잭션을 건다 — 주문 · 배송지 · 항목 · 입력을 한 스냅숏으로 읽는다.
 *
 * <p>출고 · 배송 완료 반영(#18)은 트랜잭션을 Processor 가 주문마다 건다({@code REQUIRES_NEW}) — 버전 충돌로 롤백된 뒤 트랜잭션 밖에서 {@code ORDER_025} 로 바꾸려고
 * 이 두 메서드에는 트랜잭션을 걸지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderInternalFacade implements OrderInternalUseCase {

    private final OrderProcessor orderProcessor;
    private final OrderPaymentSummaryProcessor orderPaymentSummaryProcessor;
    private final OrderPaymentSummaryInternalPresenter orderPaymentSummaryInternalPresenter;
    private final OrderFulfillmentProcessor orderFulfillmentProcessor;
    private final OrderFulfillmentInternalPresenter orderFulfillmentInternalPresenter;

    @Override
    @Transactional(readOnly = true)
    public OrderPaymentSummaryInternalResponse getPaymentSummary(long orderId, long memberId) {
        Order order = orderProcessor.getOwned(memberId, orderId);
        return orderPaymentSummaryInternalPresenter.toResponse(orderPaymentSummaryProcessor.summarize(order));
    }

    @Override
    public OrderFulfillmentInternalResponse ship(long orderId, long deliveryId, Instant shippedAt) {
        return fulfill("shipment", orderId, deliveryId, () -> orderFulfillmentProcessor.ship(orderId, shippedAt));
    }

    @Override
    public OrderFulfillmentInternalResponse completeDelivery(long orderId, long deliveryId, Instant deliveredAt) {
        return fulfill("delivery-completion", orderId, deliveryId, () -> orderFulfillmentProcessor.completeDelivery(orderId, deliveredAt));
    }

    private OrderFulfillmentInternalResponse fulfill(String action, long orderId, long deliveryId, Supplier<OrderFulfillmentInfo> transition) {
        OrderFulfillmentInfo info;
        try {
            info = transition.get();
        } catch (OptimisticLockingFailureException exception) {
            // 읽은 뒤 고객 취소 · 어드민 처리가 먼저 커밋했다 — delivery 는 송장을 남겨 두고 같은 요청을 다시 보내 지금 상태로 판정받는다
            log.warn("order {} lost to a concurrent change. orderId={}, deliveryId={}", action, orderId, deliveryId);
            throw new OrderException(OrderErrorCode.ORDER_CONCURRENTLY_CHANGED);
        }
        if (info.changed()) {
            log.info("order {} applied. orderId={}, deliveryId={}, status={}", action, orderId, deliveryId, info.status());
        }
        return orderFulfillmentInternalPresenter.toResponse(info);
    }
}
