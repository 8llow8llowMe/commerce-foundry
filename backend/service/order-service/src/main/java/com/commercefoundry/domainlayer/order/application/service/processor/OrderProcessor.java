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
import com.commercefoundry.global.properties.OrderProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
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
    private final OrderProperties orderProperties;
    private final Clock clock;

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
     * @throws OrderException {@code IDEMPOTENCY_IN_PROGRESS}(409) — 다른 시도가 기록을 이어받았거나 lease 가 {@code completion-margin} 안으로 남았다(#436, 같은 키 재시도가 같은 주문 아이디로 이어받는다).
     *     {@code ORDER_SAVE_FAILED}(500) — DB 가 거절했다
     * @throws OrderSaveException {@code ALREADY_SAVED} — 같은 주문 아이디의 주문을 다른 시도가 먼저 저장했다(부른 쪽이 그 주문을 재생한다)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public Order create(long memberId, OrderRequest request, OrderCreateCommand command, OrderQuote quote, StockReservation reservation) {
        if (request.memberId() != memberId || request.orderId() != reservation.orderId()
            || !request.matches(command.fingerprint()) || !quote.countryCode().equals(command.shippingAddress().countryCode())) {
            throw new IllegalArgumentException("Request owner does not match member");
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!reservation.expiresAt().truncatedTo(ChronoUnit.MICROS).isAfter(now)) {
            throw new OrderException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
        // lease 끝 margin 안이면 여기서 진다(#436) — 완료 커밋이 lease 끝 뒤로 밀리면 다른 시도의 수량 계수가 이 시도를 기록에서도 주문에서도 못 본다
        orderRequestRepositoryPort.completeOwned(request, now, orderProperties.creation().completionMargin());
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

    /**
     * 회원이 상품별로 이미 쥐었거나 쥐려는 수량 — 주문 생성의 상품 수량 상한에 더한다 (#426 · #436). 아래 둘의 합이다.
     *
     * <ol>
     *   <li>진행 중인 주문 요청 기록의 수량 — 처리 중 · lease 가 살아 있음 · #231 창 안, {@code excludedRequestId}(이 시도의 기록) 제외.
     *       lease 가 끝난 기록은 세지 않는다(실패한 시도는 lease 를 바로 끝낸다 — 고객이 새 키로 곧바로 다시 주문할 수 있다)</li>
     *   <li>결제 대기 주문의 수량 — 재고 선점을 쥐고 있다</li>
     * </ol>
     *
     * <p><b>(1) → (2) 순서로 읽는다.</b> 완료는 기록 COMPLETED 와 주문 INSERT 가 한 트랜잭션이라, 기록을 먼저 읽으면 그 사이 완료된 시도는
     * (1) 에서 처리 중으로 보이거나 (2) 에서 주문으로 보인다(둘 다 보이면 과잉 거절 쪽). 반대로 읽으면 주문을 읽은 뒤 완료돼 기록에서도 빠지는
     * 시도가 어느 쪽에도 안 세는 구멍이 생긴다.
     *
     * <p>퍼사드는 기록을 잡은(먼저 커밋한) 뒤 · 선점 전에 다시 부른다 — 동시에 통과한 시도 중 늦게 센 쪽이 먼저 넣은 쪽의 기록(또는 그 주문)을 반드시 보므로
     * 통과한 수량 합이 상한을 넘지 않는다. 남는 틈은 선점 뒤 저장 실패(5xx)로 lease 가 끝난 기록의 잔여뿐이고 #231 시도 상한이 묶는다.
     *
     * <p>트랜잭션을 걸지 않는다 — 두 문장이 각자 짧은 트랜잭션이다(기록 어댑터는 REQUIRES_NEW 라 여기 걸면 커넥션 두 개를 잡는다).
     *
     * @param excludedRequestId 이 시도의 기록 아이디. 아직 없으면(새 키의 견적 단계) 0
     */
    public Map<Long, Integer> heldQuantitiesByProduct(long memberId, Collection<Long> productIds, long excludedRequestId) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Instant now = clock.instant();
        Instant since = now.minus(orderProperties.creation().activeRequestWindow());
        Map<Long, Integer> held = new HashMap<>();
        // (1) 진행 중 기록 → (2) 결제 대기 주문. 순서를 바꾸지 않는다(위 주석)
        orderRequestRepositoryPort.sumInFlightQuantitiesByProduct(memberId, productIds, excludedRequestId, now, since)
            .forEach(result -> held.merge(result.productId(), Math.toIntExact(result.quantity()), Math::addExact));
        orderRepositoryPort.sumPendingQuantitiesByProduct(memberId, productIds)
            .forEach(result -> held.merge(result.productId(), Math.toIntExact(result.quantity()), Math::addExact));
        return held;
    }

    /** 회원의 주문. 없거나 다른 회원의 주문이면 {@code NOT_FOUND_ORDER}(404). */
    @Transactional(readOnly = true)
    public Order getOwned(long memberId, long orderId) {
        return orderRepositoryPort.findByIdAndMemberId(orderId, memberId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_ORDER));
    }
}
