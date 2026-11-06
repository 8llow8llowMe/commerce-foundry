package com.commercefoundry.domainlayer.order.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 * @param paymentDueAt 결제 기한 — 재고 선점 만료 시각을 DB 정밀도(마이크로초)로 자른 값이다
 * @param shippedAt 출고 시각 — {@link #ship(Instant)} 가 채운다(#18). 출고 전이면 null
 * @param deliveredAt 배송 완료 시각 — {@link #deliver(Instant)} 가 채운다(#18). 자동 구매 확정의 기준이다. 배송 완료 전이면 null.
 *     두 칸이 생기기 전에 배송 중 · 배송 완료가 된 주문은 배포 전 운영 SQL(PR 본문)로 상태 이력 시각을 채운다 — 채우지 못한 행은 null 로 남을 수 있어
 *     상태와 묶는 check 를 두지 않는다 ({@code order-flow-guide.md} §1-1)
 * @param createdAt 저장된 시각(읽기 전용). 저장 전이면 null. 주문 응답(생성 · 재생)이 접수 시각을 보여 주려고 들고 다닌다 — 쓰기({@code OrderMapper#toEntity})는
 *     이 값을 싣지 않는다. 감사({@code BaseEntity})가 채우고 {@code updatable = false} 라 merge 가 덮지 않으므로, 쓰기 쪽에서 들고 다니지 않는다는
 *     {@code coding-conventions.md} §9-1 의 규칙과 어긋나지 않는다
 * @param version 낙관적 락 버전. 저장 전이면 null
 */
public record Order(
    long id, long memberId, OrderStatus status, OrderQuote quote, List<Long> itemIds, ShippingAddress shippingAddress,
    long stockReservationId, Instant paymentDueAt, Instant shippedAt, Instant deliveredAt, Instant createdAt, Long version
) {

    /**
     * 출고 뒤 상태 — 이미 출고된 주문의 출고 요청은 바꾸지 않고 성공이다(송장 등록 재시도). 아래 배송 완료 뒤 상태와 함께, 전이 표({@link OrderStatus})에서
     * 배송 중 · 배송 완료로부터 닿는 상태와 같아야 한다 — {@code OrderTest} 가 표로 계산해 맞춘다.
     */
    private static final Set<OrderStatus> SHIPPED_OR_LATER = Collections.unmodifiableSet(EnumSet.of(
        OrderStatus.SHIPPING, OrderStatus.DELIVERED, OrderStatus.PURCHASE_CONFIRMED, OrderStatus.RETURN_REQUESTED, OrderStatus.RETURNED));

    /** 배송 완료 뒤 상태 — 이미 배송 완료된 주문의 배송 완료 요청은 바꾸지 않고 성공이다. */
    private static final Set<OrderStatus> DELIVERED_OR_LATER = Collections.unmodifiableSet(EnumSet.of(
        OrderStatus.DELIVERED, OrderStatus.PURCHASE_CONFIRMED, OrderStatus.RETURN_REQUESTED, OrderStatus.RETURNED));

    /** 결제 뒤 출고 전 상태 — 고객 결제 후 취소({@link #requestCancellation()})가 취소 요청으로 보낼 수 있는 상태다. 전이 표와 같아야 한다. */
    private static final Set<OrderStatus> PAID_BEFORE_SHIPMENT = Collections.unmodifiableSet(EnumSet.of(
        OrderStatus.PAID, OrderStatus.MAKING, OrderStatus.PREPARING));

    public Order {
        if (id <= 0 || memberId <= 0 || stockReservationId <= 0 || (version != null && version < 0)) {
            throw new IllegalArgumentException("Invalid order identifiers or version");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(shippingAddress, "shippingAddress");
        Objects.requireNonNull(paymentDueAt, "paymentDueAt");
        itemIds = List.copyOf(Objects.requireNonNull(itemIds, "itemIds"));
        if (itemIds.stream().anyMatch(value -> value <= 0) || itemIds.stream().distinct().count() != itemIds.size()) {
            throw new IllegalArgumentException("Item identifiers must be positive and unique");
        }
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
     * <p>결제 기한은 마이크로초로 자른다 — {@code payment_due_at} 은 {@code timestamp(6)} 이라 그 아래 자리는 저장되지 않고(드라이버가 반올림한다),
     * 자르지 않으면 새 주문 응답(이 값)과 같은 키 재생 응답(DB 에서 읽은 값)의 결제 기한이 달라진다.
     *
     * @param itemIdGenerator 항목 아이디 발급 (줄마다 한 번)
     * @param paymentDueAt 재고 선점 만료 시각
     * @throws IllegalArgumentException 배송지 국가가 주문서의 배송 국가와 다를 때
     */
    public static Order place(
        long id, long memberId, OrderQuote quote, LongSupplier itemIdGenerator, ShippingAddress shippingAddress, long stockReservationId, Instant paymentDueAt
    ) {
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(paymentDueAt, "paymentDueAt");
        List<Long> itemIds = Stream.generate(itemIdGenerator::getAsLong).limit(quote.lines().size()).toList();
        return new Order(id, memberId, OrderStatus.PENDING_PAYMENT, quote, itemIds, shippingAddress, stockReservationId,
            paymentDueAt.truncatedTo(ChronoUnit.MICROS), null, null, null, null);
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

    /**
     * 표({@link OrderStatus})에 있는 전이로 상태를 바꾼다 — 누가 어떤 전이를 해도 되는지는 부르는 쪽(어드민 정책 · 결제 · 클레임)이 먼저 거른다.
     *
     * 결제 전 취소(결제 대기 → 취소 완료)는 이 메서드로 하지 않는다 — 재고 해제 이벤트가 따라가야 해 {@link #cancelBeforePayment()} 를 쓴다(#16).
     * 출고(배송 중) · 배송 완료도 이 메서드로 하지 않는다 — 시각을 함께 남겨야 해 {@link #ship(Instant)} · {@link #deliver(Instant)} 를 쓴다(#18).
     * 취소 거절(취소 요청 → 요청 전 상태)도 이 메서드로 하지 않는다 — 클레임 거절과 함께 가야 해 {@link #rejectCancellation(OrderStatus)} 를 쓴다(#17).
     *
     * @throws OrderStatusTransitionException 표 밖 전이 · 저장 전 주문 · 결제 전 취소 · 출고 · 배송 완료 · 취소 거절
     */
    public Order changeStatus(OrderStatus next) {
        Objects.requireNonNull(next, "next");
        if (isNew() || (status == OrderStatus.PENDING_PAYMENT && next == OrderStatus.CANCELED)
            || (status == OrderStatus.CANCEL_REQUESTED && PAID_BEFORE_SHIPMENT.contains(next))
            || next == OrderStatus.SHIPPING || next == OrderStatus.DELIVERED) {
            throw new OrderStatusTransitionException(status, next);
        }
        return transitionTo(next);
    }

    /**
     * 출고한다 (#18) — 상품 준비중이면 배송 중으로 바꾸고 출고 시각을 남긴다. 이미 출고된 뒤(배송 중 · 배송 완료 · 구매 확정 · 반품)면 바꾸지 않은 이 값을
     * 돌려준다 — 송장 등록 재시도가 안전하고, 처음 출고 시각을 덮지 않는다.
     *
     * @param shippedAt 출고 시각 — DB 정밀도(마이크로초)로 자른다
     * @throws OrderStatusTransitionException 상품 준비중 · 출고 뒤가 아닌 상태(결제 대기 · 결제 완료 · 제작 중 · 만료 · 취소 등) · 저장 전 주문
     */
    public Order ship(Instant shippedAt) {
        Objects.requireNonNull(shippedAt, "shippedAt");
        if (SHIPPED_OR_LATER.contains(status)) {
            return this;
        }
        if (isNew() || status != OrderStatus.PREPARING) {
            throw new OrderStatusTransitionException(status, OrderStatus.SHIPPING);
        }
        return transitionTo(OrderStatus.SHIPPING, shippedAt.truncatedTo(ChronoUnit.MICROS), deliveredAt);
    }

    /**
     * 배송 완료한다 (#18) — 배송 중이면 배송 완료로 바꾸고 배송 완료 시각(자동 구매 확정 기준)을 남긴다. 이미 배송 완료된 뒤(구매 확정 · 반품 포함)면
     * 바꾸지 않은 이 값을 돌려준다. delivery-service 의 배송 완료와 어드민 수동 완료(#17)가 함께 쓴다.
     *
     * @param deliveredAt 배송 완료 시각 — DB 정밀도(마이크로초)로 자른다
     * @throws OrderStatusTransitionException 배송 중 · 배송 완료 뒤가 아닌 상태(상품 준비중 이전 · 취소 등) · 저장 전 주문
     */
    public Order deliver(Instant deliveredAt) {
        Objects.requireNonNull(deliveredAt, "deliveredAt");
        if (DELIVERED_OR_LATER.contains(status)) {
            return this;
        }
        if (isNew() || status != OrderStatus.SHIPPING) {
            throw new OrderStatusTransitionException(status, OrderStatus.DELIVERED);
        }
        return transitionTo(OrderStatus.DELIVERED, shippedAt, deliveredAt.truncatedTo(ChronoUnit.MICROS));
    }

    /** 배송 완료 기간이 지난 주문만 자동 구매 확정한다. 반품 등 다른 상태 · 시각이 없는 주문은 그대로 둔다. */
    public Order autoConfirmPurchase(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        if (isNew() || status != OrderStatus.DELIVERED || deliveredAt == null || deliveredAt.isAfter(cutoff)) {
            return this;
        }
        return transitionTo(OrderStatus.PURCHASE_CONFIRMED);
    }

    /** 맞춤 제작 항목이 하나라도 있는가 — 제작 중(MAKING) 단계는 이런 주문만 거친다 ({@code order-flow-guide.md} §1). */
    public boolean hasCustomOrderItem() {
        return quote.lines().stream().anyMatch(OrderQuoteLine::customOrder);
    }

    /**
     * 결제 전 고객 취소 (#16) — 결제 대기면 CANCELED 다. 이미 취소됐으면 바꾸지 않은 이 값을 돌려준다(같은 요청의 재시도가 안전하게).
     * 결제 뒤 취소(PAID · MAKING · PREPARING → CANCEL_REQUESTED)는 환불 · 재고 복원이 함께 가야 해 이 메서드가 아니다(#16 PR-2).
     *
     * @throws OrderStatusTransitionException 결제 대기 · 취소 완료가 아닌 상태(결제 완료 · 만료 등)
     */
    public Order cancelBeforePayment() {
        if (status == OrderStatus.CANCELED) {
            return this;
        }
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new OrderStatusTransitionException(status, OrderStatus.CANCELED);
        }
        return transitionTo(OrderStatus.CANCELED);
    }

    /**
     * 결제 완료 (#308) — 결제 대기 · 만료 주문을 결제 완료로 바꾸고 재고 선점 아이디를 확정한 선점으로 바꾼다.
     * <b>부르기 전에 선점 확정의 200 · CONFIRMED 응답을 받아야 한다</b> — 만료 주문이면 같은 주문 아이디의 재선점 뒤 확정이다
     * ({@code order-flow-guide.md} §1-2 · §3-4). 진위 확인(payment 재조회) · 결제 대조 · 취소 주문 거절은 부르는 쪽이 먼저 한다.
     *
     * @param reservationId 확정 200 을 받은 재고 선점 아이디 — 결제 대기면 보통 지금 값과 같고, 재선점했으면 새 아이디다
     * @throws OrderStatusTransitionException 결제 대기 · 만료가 아닌 상태(이미 결제 완료 · 취소 등) · 저장 전 주문
     * @throws IllegalArgumentException 선점 아이디가 양수가 아닐 때
     */
    public Order markPaid(long reservationId) {
        if (reservationId <= 0) {
            throw new IllegalArgumentException("positive reservationId required");
        }
        if (isNew() || !isAwaitingPaymentApproval() || !status.canTransitionTo(OrderStatus.PAID)) {
            throw new OrderStatusTransitionException(status, OrderStatus.PAID);
        }
        return new Order(id, memberId, OrderStatus.PAID, quote, itemIds, shippingAddress, reservationId, paymentDueAt, shippedAt, deliveredAt, createdAt, version);
    }

    /** 결제 승인을 반영할 수 있는 상태인가 — 결제 대기 · 만료 (#308). {@link #markPaid} 와 재고 확정 · 재선점이 같은 판정을 쓴다. */
    public boolean isAwaitingPaymentApproval() {
        return status == OrderStatus.PENDING_PAYMENT || status == OrderStatus.EXPIRED;
    }

    /**
     * 결제 후 고객 취소 요청 (#16 PR-2) — 결제 완료 · 제작 중 · 상품 준비중이면 취소 요청(CANCEL_REQUESTED)으로 바꾼다. 환불 · 재고 복원은 클레임이 이어 간다.
     * 이미 취소 요청 · 취소 완료면 바꾸지 않은 이 값을 돌려준다(더블클릭 · 재시도). 제작 중 허용 여부는 부르는 쪽이 {@link #isCancelableByCustomer(boolean)} 로 먼저 본다.
     *
     * @throws OrderStatusTransitionException 그 밖의 상태(결제 대기 · 만료 · 배송 중 이후 등) · 저장 전 주문
     */
    public Order requestCancellation() {
        if (status == OrderStatus.CANCEL_REQUESTED || status == OrderStatus.CANCELED) {
            return this;
        }
        if (isNew() || !PAID_BEFORE_SHIPMENT.contains(status)) {
            throw new OrderStatusTransitionException(status, OrderStatus.CANCEL_REQUESTED);
        }
        return transitionTo(OrderStatus.CANCEL_REQUESTED);
    }

    /**
     * 결제 후 취소 완료 (#16 PR-2) — 환불이 확정된 취소 요청을 취소 완료(CANCELED)로 바꾼다. 이미 취소 완료면 바꾸지 않은 이 값을 돌려준다(확정 반영 재시도).
     *
     * @throws OrderStatusTransitionException 취소 요청 · 취소 완료가 아닌 상태 · 저장 전 주문
     */
    public Order completeCancellation() {
        if (status == OrderStatus.CANCELED) {
            return this;
        }
        if (isNew() || status != OrderStatus.CANCEL_REQUESTED) {
            throw new OrderStatusTransitionException(status, OrderStatus.CANCELED);
        }
        return transitionTo(OrderStatus.CANCELED);
    }

    /**
     * 운영자 취소 거절 (#17 PR-A) — 환불이 종결 거절된 취소 요청을 요청 전 상태(결제 완료 · 제작 중 · 상품 준비중)로 되돌린다. 요청 전 상태는 부르는 쪽이
     * 상태 이력(취소 요청으로 바뀐 마지막 줄의 이전 상태)에서 찾는다. 클레임 거절과 같은 트랜잭션이어야 한다.
     *
     * @param restored 되돌릴 상태 — 결제 완료 · 제작 중 · 상품 준비중
     * @throws OrderStatusTransitionException 취소 요청이 아닌 상태 · 그 밖의 되돌릴 상태 · 저장 전 주문
     */
    public Order rejectCancellation(OrderStatus restored) {
        Objects.requireNonNull(restored, "restored");
        if (isNew() || status != OrderStatus.CANCEL_REQUESTED || !PAID_BEFORE_SHIPMENT.contains(restored)) {
            throw new OrderStatusTransitionException(status, restored);
        }
        return transitionTo(restored);
    }

    /** 결제 전 취소({@link #cancelBeforePayment()})를 할 수 있는가 — 결제 대기만이다(#16 PR-1). */
    public boolean isCancelableBeforePayment() {
        return status == OrderStatus.PENDING_PAYMENT;
    }

    /**
     * 결제 전 · 결제 후를 합친 고객 취소 가능 판정 (#16 PR-2) — 결제 대기 · 결제 완료 · 상품 준비중이고, 제작 중은 {@code allowWhileMaking} 일 때만이다.
     * 결제 후 취소의 운영 게이트(재시도 스케줄러)는 보지 않는다 — 게이트까지 합친 판정은 {@link OrderStatus#isCancelableByCustomer(boolean, boolean)} 다.
     *
     * @param allowWhileMaking {@code order.cancellation.allow-while-making} — 제작에 들어간 맞춤 상품의 취소를 고객에게 열지
     */
    public boolean isCancelableByCustomer(boolean allowWhileMaking) {
        return status.isCancelableByCustomer(allowWhileMaking, true);
    }

    public boolean isNew() {
        return version == null;
    }

    private Order transitionTo(OrderStatus next) {
        return transitionTo(next, shippedAt, deliveredAt);
    }

    private Order transitionTo(OrderStatus next, Instant nextShippedAt, Instant nextDeliveredAt) {
        if (!status.canTransitionTo(next)) {
            throw new OrderStatusTransitionException(status, next);
        }
        return new Order(id, memberId, next, quote, itemIds, shippingAddress, stockReservationId, paymentDueAt, nextShippedAt, nextDeliveredAt, createdAt, version);
    }

    @Override
    public String toString() {
        // 배송지 · 맞춤 제작 입력은 로그로 새지 않게 뺀다
        return "Order[id=" + id + ", memberId=" + memberId + ", status=" + status + ", version=" + version + "]";
    }
}
