package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.model.StockConfirmation;
import com.commercefoundry.domainlayer.order.application.model.StockReservation;
import com.commercefoundry.domainlayer.order.application.port.out.StockReservationPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockConfirmationQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 선점 (product-service, 주문 생성 순서 4) · 결제 반영 전 확정 · 재선점(#308, {@link #confirmOrReReserve}) — 원격 호출이라 <b>트랜잭션을 걸지 않는다</b>.
 * 아래 목록은 주문 생성의 선점 사유다. 사유별 사용자 오류 코드를 여기서 정한다
 * ({@code order-flow-guide.md} §3-4 · §2-4). <b>다시 보내지 않는다</b> — 같은 키의 재시도가 같은 주문 아이디로 다시 선점하고, product 는 기존 선점을 200 으로 준다.
 *
 * <ul>
 *   <li>021 재고 부족 → 409 {@code STOCK_INSUFFICIENT}, 022 판매 불가 → 409 {@code ITEM_NOT_PURCHASABLE}. product 가 한 트랜잭션으로 롤백해 선점이 남지 않는다</li>
 *   <li>026 동시 변경 · 동시 생성 → 409 {@code IDEMPOTENCY_IN_PROGRESS}(같은 키로 다시 보낸다)</li>
 *   <li>023 같은 주문의 다른 항목 → 500 {@code STOCK_RESERVATION_INCONSISTENT} + error 로그(같은 지문이면 항목도 같아야 한다 — 버그 · 데이터 이상)</li>
 *   <li>타임아웃 · 5xx · 서킷 · 그 밖의 거절 · 계약 위반 · 이미 지난 만료 시각 → 503 {@code PRODUCT_SERVICE_UNAVAILABLE}</li>
 * </ul>
 *
 * <p>product-service 의 오류 메시지(SKU 아이디 목록이 실린다)는 고객 응답에 넘기지 않는다 — 우리 오류 코드의 문구를 쓴다. 로그에는 주문 아이디만 남기고
 * 원인 예외는 타입만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStockProcessor {

    private final StockReservationPort stockReservationPort;
    private final StockRestorationPort stockRestorationPort;
    private final Clock clock;

    /**
     * 주문서의 줄을 SKU 별 수량 합으로 묶어(SKU 아이디 오름차순) 선점한다 — 맞춤 입력만 다른 같은 SKU 줄은 합친다. 합계는 50줄 × 99 = 4,950 이하라
     * product-service 의 항목 수량 상한(10,000)을 넘지 않고, 항목 수도 50개 이하라 상한(100)을 넘지 않는다.
     *
     * @param quote 같은 계산({@code OrderQuoteProcessor#quote})으로 만든 주문서
     * @throws OrderException 위 사유별 코드
     */
    @Transactional(propagation = Propagation.NEVER)
    public StockReservation reserve(long orderId, OrderQuote quote) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("positive orderId required");
        }
        SortedMap<Long, Integer> quantitiesBySkuId = quantitiesBySkuId(quote);
        StockReservationQueryResult result;
        try {
            result = stockReservationPort.reserve(orderId, quantitiesBySkuId);
        } catch (StockReservationException exception) {
            throw toOrderException(orderId, exception);
        }
        if (result == null || result.reservationId() <= 0 || result.orderId() != orderId || !result.expiresAt().isAfter(clock.instant())) {
            // 다른 주문의 선점이거나 이미 지난 기한 — 결제 기한이 과거인 주문을 만들지 않는다. 같은 키로 다시 보내면 다시 확인한다
            log.error("stock reservation result unusable. orderId={}", orderId);
            throw new OrderException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
        return new StockReservation(result.reservationId(), result.orderId(), result.expiresAt());
    }

    /** SKU 아이디 → 수량 합 (SKU 아이디 오름차순). */
    static SortedMap<Long, Integer> quantitiesBySkuId(OrderQuote quote) {
        SortedMap<Long, Integer> quantities = new TreeMap<>();
        quote.lines().forEach(line -> {
            if (line.skuId() <= 0 || line.quantity() <= 0) {
                throw new IllegalArgumentException("positive SKU and quantity required");
            }
            quantities.merge(line.skuId(), line.quantity(), Math::addExact);
        });
        return quantities;
    }

    private static OrderException toOrderException(long orderId, StockReservationException exception) {
        return switch (exception.getFailure()) {
            case INSUFFICIENT -> new OrderException(OrderErrorCode.STOCK_INSUFFICIENT, exception);
            // 어떤 SKU 가 숨겨졌는지 알 수 없으므로 이전 견적의 이름도 노출하지 않는다.
            case NOT_PURCHASABLE -> new OrderException(OrderErrorCode.ITEM_NOT_PURCHASABLE, "판매하지 않는 상품");
            case CONFLICT -> new OrderException(OrderErrorCode.IDEMPOTENCY_IN_PROGRESS, exception);
            case ITEMS_MISMATCH -> {
                log.error("stock reservation items mismatch for same order. orderId={}", orderId);
                yield new OrderException(OrderErrorCode.STOCK_RESERVATION_INCONSISTENT, exception);
            }
            // RELEASED · NOT_FOUND 는 확정에서만 나온다 — 선점에서 오면 모르는 거절과 같다
            case UNKNOWN, REJECTED, RELEASED, NOT_FOUND, CONFIRMED, NOT_CONFIRMED, QUANTITY_EXCEEDED, RESTORATION_MISMATCH -> {
                log.warn("stock reservation failed. orderId={}, failure={}", orderId, exception.getFailure());
                yield new OrderException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE, exception);
            }
        };
    }

    /**
     * 결제 완료 반영 전 재고 확정 (#308, {@code order-flow-guide.md} §3-4) — 원격 호출이라 <b>트랜잭션을 걸지 않는다</b>. 주문은 바꾸지 않는다 —
     * 결과의 선점 아이디로 부른 쪽이 새 트랜잭션에서 {@code Order#markPaid} 한다. 여기서 다시 보내지 않는다 — 다시 부르는 것은 결제 이벤트 재전달이고,
     * 확정 · 재선점은 모두 멱등이라(확정은 이미 CONFIRMED 면 200, 재선점은 같은 주문의 활성 선점을 200) 재전달에서 처음부터 다시 불러도 재고가 두 번 움직이지 않는다.
     *
     * <ol>
     *   <li>주문의 선점을 확정한다 — 200 · CONFIRMED 면 그 선점이다.</li>
     *   <li>409 {@code PRODUCT_025}(해제 · 만료) · 404 {@code PRODUCT_003}(없음)이면 같은 주문 아이디 · 주문 항목 스냅숏의 SKU 별 수량 합으로 재선점하고
     *       <b>곧바로</b> 그 선점을 확정한다 — 사이를 비우면 새 선점도 TTL 뒤 다시 만료된다. 재선점 응답의 만료 시각은 보지 않는다(지났을 수 있다).</li>
     *   <li>재선점이 {@code PRODUCT_021}(재고 부족) · {@code PRODUCT_022}(판매 불가)면 {@link StockConfirmation#stockUnavailable()} — 아무것도 잡지 않았다.</li>
     * </ol>
     *
     * <p>예외 — 부른 쪽(컨슈머)이 재시도 여부를 오류 코드로 가른다:
     * <ul>
     *   <li>503 {@code PRODUCT_SERVICE_UNAVAILABLE} — 다시 부르면 풀릴 수 있다: 동시 변경 {@code PRODUCT_026} · 타임아웃 · 5xx · 서킷 오픈 · 모르는 4xx ·
     *       읽을 수 없는 성공 응답 · 재선점 직후 확정이 다시 해제 · 없음.</li>
     *   <li>500 {@code STOCK_RESERVATION_INCONSISTENT} — 다시 불러도 같다(error 로그): 같은 주문의 선점과 항목이 다름({@code PRODUCT_023}) · 다른 주문의 선점이 확정됨.</li>
     * </ul>
     *
     * @param order 결제 대기 · 만료 주문 — 결제 전 취소된 주문은 확정 · 재선점 없이 부른 쪽이 거절한다
     * @throws IllegalArgumentException 결제 대기 · 만료가 아닌 주문
     */
    @Transactional(propagation = Propagation.NEVER)
    public StockConfirmation confirmOrReReserve(Order order) {
        return confirmOrReReserve(order, reservationId -> {
        });
    }

    /**
     * {@link #confirmOrReReserve(Order)} 과 같고, 재선점이 <b>새 선점 아이디</b>를 주면 그 선점을 확정하기 <b>전에</b> {@code beforeReReservedConfirm} 을 부른다
     * (#308 PR-C1 — 재선점 증거를 남겨 확정 뒤 끊긴 복구창에서 찾게 한다). 콜백이 실패하면 확정을 부르지 않고 그 예외가 그대로 나간다.
     */
    @Transactional(propagation = Propagation.NEVER)
    public StockConfirmation confirmOrReReserve(Order order, LongConsumer beforeReReservedConfirm) {
        return confirmOrReReserve(order, beforeReReservedConfirm, reservationId -> true);
    }

    /** 모든 confirm 직전에 영속 복원 의도를 검사한다. 진행 중인 confirm의 경쟁은 최종 DB 결정에서 다시 검사한다. */
    @Transactional(propagation = Propagation.NEVER)
    public StockConfirmation confirmOrReReserve(Order order, LongConsumer beforeReReservedConfirm, LongPredicate canConfirm) {
        if (!order.isAwaitingPaymentApproval()) {
            throw new IllegalArgumentException("결제 대기 · 만료 주문만 재고를 확정합니다: orderId=" + order.id() + ", status=" + order.status());
        }
        SortedMap<Long, Integer> quantitiesBySkuId = quantitiesBySkuId(order.quote());
        if (!canConfirm.test(order.stockReservationId())) {
            return StockConfirmation.stockUnavailable();
        }
        try {
            return StockConfirmation.confirmed(confirmOwn(order.id(), order.stockReservationId()));
        } catch (StockReservationException exception) {
            if (exception.getFailure() != StockReservationException.Failure.RELEASED && exception.getFailure() != StockReservationException.Failure.NOT_FOUND) {
                throw toConfirmationException(order.id(), exception);
            }
            log.info("stock reservation gone before payment, re-reserving. orderId={}, failure={}", order.id(), exception.getFailure());
        }
        StockReservationQueryResult reReserved;
        try {
            reReserved = stockReservationPort.reReserve(order.id(), quantitiesBySkuId);
        } catch (StockReservationException exception) {
            if (exception.getFailure() == StockReservationException.Failure.INSUFFICIENT
                || exception.getFailure() == StockReservationException.Failure.NOT_PURCHASABLE) {
                log.warn("stock unavailable for paid order re-reservation. orderId={}, failure={}", order.id(), exception.getFailure());
                return StockConfirmation.stockUnavailable();
            }
            throw toConfirmationException(order.id(), exception);
        }
        if (reReserved == null || reReserved.orderId() != order.id()) {
            log.error("stock re-reservation result belongs to another order. orderId={}", order.id());
            throw new OrderException(OrderErrorCode.STOCK_RESERVATION_INCONSISTENT);
        }
        if (reReserved.reservationId() != order.stockReservationId()) {
            beforeReReservedConfirm.accept(reReserved.reservationId());
        }
        if (!canConfirm.test(reReserved.reservationId())) {
            return StockConfirmation.stockUnavailable();
        }
        try {
            return StockConfirmation.reReservedAndConfirmed(confirmOwn(order.id(), reReserved.reservationId()));
        } catch (StockReservationException exception) {
            throw toConfirmationException(order.id(), exception);
        }
    }

    /**
     * 취소 주문의 결제 반영 복구창 프로브 (#308 PR-C1, {@code order-flow-guide.md} §3-6) — 트랜잭션 없음. 후보 선점마다 해제를 불러 확정된 선점을 찾는다.
     * 후보는 재선점 증거 · 주문의 선점 아이디 둘뿐이라 원격 호출은 최대 두 번이다(벌크 해제 API 없음). 재선점(reserve)을 조회 대용으로 쓰지 않는다.
     *
     * <ul>
     *   <li>200 — RESERVED 를 풀었거나 이미 RELEASED. 취소 주문이니 풀린 것이 맞고 누수가 없다.</li>
     *   <li>409 {@code PRODUCT_024} — 확정 선점 증거. 그 선점을 돌려준다(부른 쪽이 복원한다).</li>
     *   <li>404 {@code PRODUCT_003} — 후보가 아니다.</li>
     *   <li>그 밖({@code PRODUCT_026} · {@code PRODUCT_031} · 타임아웃 · 5xx · 서킷 · 모르는 4xx · 계약 위반) — 503 {@code PRODUCT_SERVICE_UNAVAILABLE}(재전달로 다시 한다).</li>
     *   <li>확정 선점이 둘 — 500 {@code STOCK_RESERVATION_INCONSISTENT}(재선점은 앞 선점이 풀렸을 때만 하므로 둘 다 확정일 수 없다).</li>
     * </ul>
     *
     * @param candidates 앞쪽부터 부른다 — 같은 아이디는 한 번만
     * @return 확정된 선점 아이디. 없으면 빈 값
     */
    @Transactional(propagation = Propagation.NEVER)
    public OptionalLong findConfirmedByReleaseProbe(long orderId, List<Long> candidates) {
        OptionalLong confirmed = OptionalLong.empty();
        for (long reservationId : new LinkedHashSet<>(candidates)) {
            try {
                stockReservationPort.release(reservationId, orderId);
                log.info("canceled order reservation released by probe. orderId={}, reservationId={}", orderId, reservationId);
            } catch (StockReservationException exception) {
                if (exception.getFailure() == StockReservationException.Failure.NOT_FOUND) {
                    continue;
                }
                if (exception.getFailure() != StockReservationException.Failure.CONFIRMED) {
                    throw toConfirmationException(orderId, exception);
                }
                if (confirmed.isPresent()) {
                    log.error("two confirmed stock reservations for canceled order. orderId={}", orderId);
                    throw new OrderException(OrderErrorCode.STOCK_RESERVATION_INCONSISTENT, exception);
                }
                log.warn("canceled order holds confirmed stock reservation, restoring. orderId={}, reservationId={}", orderId, reservationId);
                confirmed = OptionalLong.of(reservationId);
            }
        }
        return confirmed;
    }

    /**
     * 확정 뒤 취소가 이긴 결제의 재고 복원 (#308 PR-C1, {@code order-flow-guide.md} §3-4) — 트랜잭션 없음. 항목은 주문 생성 · 재선점과 같은 SKU 별 수량 합이다.
     * 클레임 아이디는 {@code order_payment.restoration_claim_id} 그대로라 재시도 · 재전달이 재고를 두 번 늘리지 않는다.
     *
     * <ul>
     *   <li>200 — 복원됨(같은 클레임의 기존 복원 포함).</li>
     *   <li>409 {@code PRODUCT_028} — 일부만 복원됐어도 반환된다. 전량 복원 증거가 아니므로 미종결 의도 유지 · DLT · 운영 확인.</li>
     *   <li>409 {@code PRODUCT_027} · {@code PRODUCT_029} · 404 {@code PRODUCT_003} — 500 {@code STOCK_RESERVATION_INCONSISTENT}(다시 해도 같다, 버그 신호).</li>
     *   <li>그 밖({@code PRODUCT_026} · {@code PRODUCT_030} · {@code PRODUCT_031} · 타임아웃 · 5xx · 서킷 · 모르는 4xx · 계약 위반) — 503 {@code PRODUCT_SERVICE_UNAVAILABLE}.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.NEVER)
    public void restoreCanceledPayment(long orderId, long paymentId, long claimId, long reservationId, OrderQuote quote) {
        try {
            stockRestorationPort.restoreCancellation(claimId, reservationId, quantitiesBySkuId(quote));
        } catch (StockReservationException exception) {
            switch (exception.getFailure()) {
                case QUANTITY_EXCEEDED -> {
                    log.error("stock restoration quantity requires manual verification. orderId={}, paymentId={}, reservationId={}, claimId={}",
                        orderId, paymentId, reservationId, claimId);
                    throw new OrderException(OrderErrorCode.STOCK_RESERVATION_INCONSISTENT);
                }
                case NOT_CONFIRMED, RESTORATION_MISMATCH, NOT_FOUND -> {
                    log.error("stock restoration rejected permanently. orderId={}, reservationId={}, failure={}", orderId, reservationId, exception.getFailure());
                    throw new OrderException(OrderErrorCode.STOCK_RESERVATION_INCONSISTENT, exception);
                }
                default -> {
                    log.warn("stock restoration failed. orderId={}, failure={}", orderId, exception.getFailure());
                    throw new OrderException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE, exception);
                }
            }
        }
    }

    /** 확정하고 그 선점이 이 주문의 것인지 본다. */
    private long confirmOwn(long orderId, long reservationId) {
        StockConfirmationQueryResult confirmed = stockReservationPort.confirm(reservationId);
        if (confirmed == null || confirmed.reservationId() != reservationId || confirmed.orderId() != orderId) {
            log.error("confirmed stock reservation belongs to another order. orderId={}, reservationId={}", orderId, reservationId);
            throw new OrderException(OrderErrorCode.STOCK_RESERVATION_INCONSISTENT);
        }
        return reservationId;
    }

    private static OrderException toConfirmationException(long orderId, StockReservationException exception) {
        if (exception.getFailure() == StockReservationException.Failure.ITEMS_MISMATCH) {
            log.error("stock re-reservation items mismatch for same order. orderId={}", orderId);
            return new OrderException(OrderErrorCode.STOCK_RESERVATION_INCONSISTENT, exception);
        }
        log.warn("stock confirmation failed. orderId={}, failure={}", orderId, exception.getFailure());
        return new OrderException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE, exception);
    }
}
