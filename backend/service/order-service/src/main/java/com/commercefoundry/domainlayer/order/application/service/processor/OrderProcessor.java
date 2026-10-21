package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.command.OrderCreateCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.OrderSaveException;
import com.commercefoundry.domainlayer.order.application.model.StockReservation;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRequestRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문의 DB 구간. 원격 호출(SKU 조회 · 배송비 · 재고 선점)은 Facade 가 이 트랜잭션 밖에서 먼저 끝내고 결과만 넘긴다 —
 * DB 커넥션을 잡은 채 원격 응답을 기다리지 않게 메서드마다 트랜잭션을 좁힌다 ({@code architecture-guide.md} §3 Facade).
 *
 * <p>소유 확인은 회원 아이디로만 한다 — 다른 회원의 주문은 없는 주문과 같이 {@code NOT_FOUND_ORDER}(404)다.
 */
@Component
@RequiredArgsConstructor
public class OrderProcessor {

    private final OrderRepositoryPort orderRepositoryPort;
    private final OrderRequestRepositoryPort orderRequestRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 프론트가 보여 준 총액과 지금 계산한 총액을 대조한다 (생성 순서 2의 끝) — DB · 원격에 닿지 않아 트랜잭션이 없다. 계산에는 쓰지 않는다.
     *
     * @param expectedTotalAmount 요청의 {@code expectedTotalAmount}. 없으면(null) 대조하지 않는다
     * @throws OrderException {@code ORDER_AMOUNT_CHANGED}(409) — 금액 · 통화가 다르다. 재고를 잡기 전이다
     */
    public void requireExpectedTotal(OrderQuote quote, Money expectedTotalAmount) {
        if (expectedTotalAmount != null && !expectedTotalAmount.equals(quote.totalAmount())) {
            throw new OrderException(OrderErrorCode.ORDER_AMOUNT_CHANGED);
        }
    }

    /**
     * 주문 저장 (생성 순서 5) — 한 트랜잭션에서 주문 요청 기록을 완료로 바꾸고(이어받을 때 받은 버전과 비교) 주문 · 배송지 · 항목 · 맞춤 제작 입력 ·
     * 첫 상태 이력(null → PENDING_PAYMENT, 고객)을 넣는다. 기록을 <b>먼저</b> 바꾼다 — 다른 시도가 기록을 이어받았으면 여기서 지고 주문을 넣지 않는다.
     *
     * @param request 이 시도가 잡은 기록 (주문 아이디 · 버전이 들어 있다)
     * @param quote 같은 계산({@code OrderQuoteProcessor#quote})으로 만든 금액
     * @param reservation 이 주문 아이디로 잡은 재고 선점 — 만료 시각이 결제 기한이다
     * @throws OrderException {@code IDEMPOTENCY_IN_PROGRESS}(409) — 다른 시도가 기록을 이어받았다. {@code ORDER_SAVE_FAILED}(500) — DB 가 거절했다
     * @throws OrderSaveException {@code ALREADY_SAVED} — 같은 주문 아이디의 주문을 다른 시도가 먼저 저장했다(부른 쪽이 그 주문을 재생한다)
     */
    @Transactional
    public Order create(long memberId, OrderRequest request, OrderCreateCommand command, OrderQuote quote, StockReservation reservation) {
        if (orderRequestRepositoryPort.saveIfUnchanged(request.complete()).isEmpty()) {
            throw new OrderException(OrderErrorCode.IDEMPOTENCY_IN_PROGRESS);
        }
        Order order = Order.place(request.orderId(), memberId, quote, snowflakeIdGenerator::generateId, command.shippingAddress(),
            reservation.reservationId(), reservation.expiresAt());
        try {
            return orderRepositoryPort.insert(order, OrderStatusHistory.created(snowflakeIdGenerator.generateId(), order));
        } catch (OrderSaveException exception) {
            if (exception.getReason() == OrderSaveException.Reason.ALREADY_SAVED) {
                throw exception;
            }
            throw new OrderException(OrderErrorCode.ORDER_SAVE_FAILED);
        }
    }

    /** 회원의 주문. 없거나 다른 회원의 주문이면 {@code NOT_FOUND_ORDER}(404). */
    @Transactional(readOnly = true)
    public Order getOwned(long memberId, long orderId) {
        return orderRepositoryPort.findByIdAndMemberId(orderId, memberId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_ORDER));
    }
}
