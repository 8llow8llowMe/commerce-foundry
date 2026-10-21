package com.commercefoundry.domainlayer.order.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * 주문 — 불변 값이다. 상태는 아래 도메인 메서드로만 바꾸고(새 값을 돌려준다), 표 밖 전이는 {@link OrderStatusTransitionException} 이다
 * ({@code order-flow-guide.md} §1-1). {@code setStatus} 를 두지 않는다. 고객에게 보이는 주문 식별자는 주문 아이디(Snowflake 문자열) 하나다.
 *
 * <p>항목 · 금액 · 배송비는 주문서({@link OrderQuote}) 그대로 스냅숏한다 — 미리보기와 같은 계산으로 만든 값이고, 생성 뒤 바뀌지 않는다.
 * 읽어 올 때도 {@link OrderQuote} 로 다시 조립해 금액 불변식(상품 합계 · 총액 · 배송비 산식 · 범위)을 다시 본다.
 *
 * @param quote 주문 시점 주문서 — 줄 순서가 항목의 줄 번호(1부터) 순서다
 * @param itemIds 항목 아이디 — {@code quote.lines()} 와 같은 순서 · 같은 개수
 * @param stockReservationId product-service 재고 선점 아이디 — 만료 뒤 재선점(#14)하면 바뀐다
 * @param paymentDueAt 결제 기한 — 재고 선점 만료 시각과 같다
 * @param createdAt 저장된 시각. 저장 전이면 null
 * @param version 낙관적 락 버전. 저장 전이면 null
 */
public record Order(
    long id, long memberId, OrderStatus status, OrderQuote quote, List<Long> itemIds, ShippingAddress shippingAddress,
    long stockReservationId, Instant paymentDueAt, Instant createdAt, Long version
) {

    public Order {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(shippingAddress, "shippingAddress");
        Objects.requireNonNull(paymentDueAt, "paymentDueAt");
        itemIds = List.copyOf(Objects.requireNonNull(itemIds, "itemIds"));
        if (itemIds.size() != quote.lines().size()) {
            throw new IllegalArgumentException("항목 아이디 수가 주문서 줄 수와 다릅니다: %d ≠ %d".formatted(itemIds.size(), quote.lines().size()));
        }
        if (!quote.countryCode().equals(shippingAddress.countryCode())) {
            throw new IllegalArgumentException("주문서의 배송 국가와 배송지 국가가 다릅니다");
        }
    }

    /**
     * 주문을 낸다 — 결제 대기(PENDING_PAYMENT)로 시작한다. 저장 전이라 버전 · 생성 시각이 없다.
     *
     * @param itemIdGenerator 항목 아이디 발급 (줄마다 한 번)
     * @throws IllegalArgumentException 배송지 국가가 주문서의 배송 국가와 다를 때
     */
    public static Order place(
        long id, long memberId, OrderQuote quote, LongSupplier itemIdGenerator, ShippingAddress shippingAddress, long stockReservationId, Instant paymentDueAt
    ) {
        Objects.requireNonNull(quote, "quote");
        List<Long> itemIds = Stream.generate(itemIdGenerator::getAsLong).limit(quote.lines().size()).toList();
        return new Order(id, memberId, OrderStatus.PENDING_PAYMENT, quote, itemIds, shippingAddress, stockReservationId, paymentDueAt, null, null);
    }

    /**
     * 결제 기한이 지났으면 만료한다 — {@code PENDING_PAYMENT && paymentDueAt <= now} 일 때만 EXPIRED 다. 이미 EXPIRED 거나 아직 기한 전이면
     * 바꾸지 않은 이 값을 돌려준다(만료 스윕 #96 이 같은 주문을 두 번 봐도 안전하게).
     *
     * @throws OrderStatusTransitionException 결제 대기 · 만료가 아닌 상태(결제 완료 등)
     */
    public Order expire(Instant now) {
        if (status == OrderStatus.EXPIRED) {
            return this;
        }
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new OrderStatusTransitionException(status, OrderStatus.EXPIRED);
        }
        if (paymentDueAt.isAfter(now)) {
            return this;
        }
        return transitionTo(OrderStatus.EXPIRED);
    }

    public boolean isNew() {
        return version == null;
    }

    private Order transitionTo(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new OrderStatusTransitionException(status, next);
        }
        return new Order(id, memberId, next, quote, itemIds, shippingAddress, stockReservationId, paymentDueAt, createdAt, version);
    }

    @Override
    public String toString() {
        // 배송지 · 맞춤 제작 입력은 로그로 새지 않게 뺀다
        return "Order[id=" + id + ", memberId=" + memberId + ", status=" + status + ", version=" + version + "]";
    }
}
