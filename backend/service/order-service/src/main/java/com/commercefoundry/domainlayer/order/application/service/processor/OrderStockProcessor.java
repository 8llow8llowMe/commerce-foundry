package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.model.StockReservation;
import com.commercefoundry.domainlayer.order.application.port.out.StockReservationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 재고 선점 (product-service, 주문 생성 순서 4) — 원격 호출이라 <b>트랜잭션을 걸지 않는다</b>. 사유별 사용자 오류 코드를 여기서 정한다
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

    private static final String NAME_DELIMITER = ", ";

    private final StockReservationPort stockReservationPort;
    private final Clock clock;

    /**
     * 주문서의 줄을 SKU 별 수량 합으로 묶어(SKU 아이디 오름차순) 선점한다 — 맞춤 입력만 다른 같은 SKU 줄은 합친다. 합계는 50줄 × 99 = 4,950 이하라
     * product-service 의 항목 수량 상한(10,000)을 넘지 않고, 항목 수도 50개 이하라 상한(100)을 넘지 않는다.
     *
     * @param quote 같은 계산({@code OrderQuoteProcessor#quote})으로 만든 주문서
     * @throws OrderException 위 사유별 코드
     */
    public StockReservation reserve(long orderId, OrderQuote quote) {
        SortedMap<Long, Integer> quantitiesBySkuId = quantitiesBySkuId(quote);
        StockReservationQueryResult result;
        try {
            result = stockReservationPort.reserve(orderId, quantitiesBySkuId);
        } catch (StockReservationException exception) {
            throw toOrderException(orderId, quote, exception);
        }
        if (result.orderId() != orderId || !result.expiresAt().isAfter(clock.instant())) {
            // 다른 주문의 선점이거나 이미 지난 기한 — 결제 기한이 과거인 주문을 만들지 않는다. 같은 키로 다시 보내면 다시 확인한다
            log.error("stock reservation result unusable. orderId={}, reservationId={}", orderId, result.reservationId());
            throw new OrderException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
        return new StockReservation(result.reservationId(), result.orderId(), result.expiresAt());
    }

    /** SKU 아이디 → 수량 합 (SKU 아이디 오름차순). */
    static SortedMap<Long, Integer> quantitiesBySkuId(OrderQuote quote) {
        SortedMap<Long, Integer> quantities = new TreeMap<>();
        quote.lines().forEach(line -> quantities.merge(line.skuId(), line.quantity(), Integer::sum));
        return quantities;
    }

    private static OrderException toOrderException(long orderId, OrderQuote quote, StockReservationException exception) {
        return switch (exception.getFailure()) {
            case INSUFFICIENT -> new OrderException(OrderErrorCode.STOCK_INSUFFICIENT, exception);
            // SKU 조회 뒤 그 사이 판매 종료 · 숨김 · 품절. 원격 메시지의 SKU 목록은 쓰지 않는다 — 어느 줄인지 몰라 주문서의 줄 이름을 모두 싣는다
            case NOT_PURCHASABLE -> new OrderException(OrderErrorCode.ITEM_NOT_PURCHASABLE, namesOf(quote));
            case CONFLICT -> new OrderException(OrderErrorCode.IDEMPOTENCY_IN_PROGRESS, exception);
            case ITEMS_MISMATCH -> {
                log.error("stock reservation items mismatch for same order. orderId={}", orderId);
                yield new OrderException(OrderErrorCode.STOCK_RESERVATION_INCONSISTENT, exception);
            }
            case UNKNOWN, REJECTED, NOT_FOUND, ALREADY_CONFIRMED, ALREADY_RELEASED -> {
                log.warn("stock reservation failed. orderId={}, failure={}", orderId, exception.getFailure());
                yield new OrderException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE, exception);
            }
        };
    }

    /** 고객이 알아볼 줄 이름 — {@code OrderQuoteProcessor} 와 같은 규칙("상품명(옵션명)", 옵션이 없으면 상품명), 같은 이름은 한 번. */
    private static String namesOf(OrderQuote quote) {
        Set<String> names = new LinkedHashSet<>();
        for (OrderQuoteLine line : quote.lines()) {
            names.add(line.optionName().isEmpty() ? line.productName() : "%s(%s)".formatted(line.productName(), line.optionName()));
        }
        return String.join(NAME_DELIMITER, names);
    }
}
