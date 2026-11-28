package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 결제 후 취소의 환불액 산식과 취소 정리 판정 (#16 PR-2 · #684, {@code order-flow-guide.md} §1-4 · §5 · §2 "클레임의 배송비 재부과는 주문 스냅숏으로") — 현재 배송비 정책이
 * 아니라 주문 시점 스냅숏({@link OrderQuote} · {@link ShippingFeeQuote})만 쓴다.
 *
 * <ul>
 *   <li>{@code refund = due(취소 전 남은 수량) − due(취소 뒤 남은 수량)}, {@code due(S) = items(S) + ship(S)}, {@code ship(∅) = 0}</li>
 *   <li>아무것도 취소하지 않은 원주문의 {@code due} 는 결제 총액이다 — 전체 취소는 총액 전액(배송비 포함)을 돌려준다</li>
 *   <li>원주문이 무료배송이 아니면 남은 주문의 배송비는 스냅숏 배송비 그대로다</li>
 *   <li>무료배송이었고 남은 상품 합계가 무료배송 기준 미만이면 스냅숏 배송비에 기본 배송비(해외는 + 무게 요금)를 다시 부과한다</li>
 *   <li>도서산간 추가 배송비는 스냅숏 배송비에 이미 들어 있어 따로 다시 부과하지 않는다</li>
 * </ul>
 *
 * <p>환불 내역({@link #breakdown})은 같은 산식을 나눈 값이다 — {@code itemsAmount = items(before) − items(after)}, {@code delta = ship(after) − ship(before)} 이고
 * {@code delta > 0} 이면 재부과, {@code delta < 0} 이면 배송비 환불이다. 내역 합({@code itemsAmount + shippingFeeRefund − shippingFeeRecharge})은 늘 {@link #refund} 와 같다.
 *
 * <p>부분 취소를 순서대로 다 하면 환불 합은 결제 총액과 같다(망원 합). 소액 항목을 취소해 재부과액이 그 항목 금액 이상이면 결과가 0 이하다 — 계산은 그대로 하고
 * {@link Refund#isPayable()} · {@link RefundBreakdown#isPayable()} 로 드러낸다. <b>0 이하 환불의 부분 취소는 접수하지 않는다</b>(사용자 결정, 고객 API #684 PR-4 에서 409 —
 * 반품의 "0 이하면 승인 거부"와 같다). 0 으로 깎아 받거나 고객에게 추가 결제를 받지 않는다.
 */
public final class CancellationRefundPolicy {

    private CancellationRefundPolicy() {
    }

    /** 전체 취소 환불액 — 결제 총액 전액이다. */
    public static Money fullRefund(Order order) {
        Objects.requireNonNull(order, "order");
        return Money.of(due(order.quote(), originalQuantities(order.quote())), order.quote().currency());
    }

    /**
     * 부분 취소 환불액 — {@link #breakdown} 의 합이다.
     *
     * @param alreadyCanceled 이미 취소된 주문 항목별 수량 (주문 항목 아이디 → 수량). 없으면 빈 맵
     * @param toCancel 이번에 취소할 주문 항목별 수량 (1 이상)
     * @throws IllegalArgumentException 주문에 없는 항목 · 0 이하 수량 · 원래 수량을 넘는 누적 취소
     */
    public static Refund refund(Order order, Map<Long, Integer> alreadyCanceled, Map<Long, Integer> toCancel) {
        return breakdown(order, alreadyCanceled, toCancel).toRefund();
    }

    /**
     * 부분 취소 환불 내역 (#684) — 취소한 상품 금액 · 돌려주는 배송비 · 다시 받는 배송비로 나눈다. 인자 규칙은 {@link #refund} 와 같다.
     *
     * @throws IllegalArgumentException 주문에 없는 항목 · 0 이하 수량 · 원래 수량을 넘는 누적 취소
     */
    public static RefundBreakdown breakdown(Order order, Map<Long, Integer> alreadyCanceled, Map<Long, Integer> toCancel) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(alreadyCanceled, "alreadyCanceled");
        Objects.requireNonNull(toCancel, "toCancel");
        if (toCancel.isEmpty() || toCancel.values().stream().anyMatch(quantity -> quantity == null || quantity < 1)
            || alreadyCanceled.values().stream().anyMatch(quantity -> quantity == null || quantity < 0)) {
            throw new IllegalArgumentException("취소 수량은 1 이상, 이미 취소된 수량은 0 이상이어야 합니다");
        }
        List<Long> itemIds = order.itemIds();
        if (!itemIds.containsAll(alreadyCanceled.keySet()) || !itemIds.containsAll(toCancel.keySet())) {
            throw new IllegalArgumentException("주문에 없는 항목입니다: orderId=" + order.id());
        }
        OrderQuote quote = order.quote();
        int[] before = originalQuantities(quote);
        int[] after = new int[before.length];
        for (int index = 0; index < before.length; index++) {
            long itemId = itemIds.get(index);
            before[index] -= alreadyCanceled.getOrDefault(itemId, 0);
            after[index] = before[index] - toCancel.getOrDefault(itemId, 0);
            if (after[index] < 0) {
                throw new IllegalArgumentException("누적 취소 수량이 주문 수량을 넘습니다: orderItemId=" + itemId);
            }
        }
        Due dueBefore = dueOf(quote, before);
        Due dueAfter = dueOf(quote, after);
        long delta = Math.subtractExact(dueAfter.shipping(), dueBefore.shipping());
        return new RefundBreakdown(Math.subtractExact(dueBefore.items(), dueAfter.items()), Math.max(0, -delta), Math.max(0, delta), quote.currency());
    }

    /**
     * 남은 수량 전부를 취소하는 전체 취소 (#684 PR-2) — 거절 밖 취소 클레임({@link Claim#reservesCancellationQuantity()})에 잡히지 않은 주문 수량 전부와 그 환불액
     * {@code due(before)} 다. 선행 취소가 없으면 전 항목 · 전 수량 · 결제 총액({@link #fullRefund})과 같다. 정산된 취소의 환불 합도 함께 낸다 — 부르는 쪽이
     * {@code 정산된 취소 환불 합 + 이번 환불 = 결제 금액} 으로 결제 기록과 대조한다.
     *
     * <p>데이터 이상은 계산하지 않는다(부르는 쪽이 접수하지 않는다) — 열린 취소가 있음(주문당 열린 취소는 하나이고 그동안 주문은 취소 요청이라 전체 취소 접수에
     * 오지 않는다), 취소에 잡힌 수량이 주문 수량을 넘음, 주문에 없는 항목, 남은 수량이 없음, 주문과 다른 통화의 환불액.
     *
     * @param claims 이 주문의 클레임 전부 — 주문 잠금 뒤 읽은 것
     * @throws IllegalArgumentException 다른 주문의 클레임
     * @throws IllegalStateException 위 데이터 이상
     */
    public static RemainingCancellation remaining(Order order, List<Claim> claims) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(claims, "claims");
        CurrencyCode currency = order.quote().currency();
        Map<Long, Integer> canceled = new HashMap<>();
        long settledRefund = 0;
        for (Claim claim : claims) {
            if (claim.orderId() != order.id()) {
                throw new IllegalArgumentException("다른 주문의 클레임입니다: claimId=" + claim.id());
            }
            if (!claim.reservesCancellationQuantity()) {
                continue;
            }
            if (claim.isOpenCancellation()) {
                throw new IllegalStateException("열린 취소가 있는 주문은 전체 취소를 접수하지 않습니다: claimId=" + claim.id());
            }
            if (claim.expectedRefund().currency() != currency) {
                throw new IllegalStateException("주문과 다른 통화의 취소 환불입니다: claimId=" + claim.id());
            }
            settledRefund = Math.addExact(settledRefund, claim.expectedRefund().amount());
            for (ClaimItem item : claim.items()) {
                if (!order.itemIds().contains(item.orderItemId())) {
                    throw new IllegalStateException("주문에 없는 항목의 취소입니다: claimId=%d, orderItemId=%d".formatted(claim.id(), item.orderItemId()));
                }
                canceled.merge(item.orderItemId(), item.quantity(), Math::addExact);
            }
        }
        Map<Long, Integer> remaining = new HashMap<>();
        for (int index = 0; index < order.itemIds().size(); index++) {
            long itemId = order.itemIds().get(index);
            int left = order.quote().lines().get(index).quantity() - canceled.getOrDefault(itemId, 0);
            if (left < 0) {
                throw new IllegalStateException("취소에 잡힌 수량이 주문 수량을 넘습니다: orderItemId=" + itemId);
            }
            if (left > 0) {
                remaining.put(itemId, left);
            }
        }
        if (remaining.isEmpty()) {
            throw new IllegalStateException("취소할 남은 수량이 없습니다: orderId=" + order.id());
        }
        Refund refund = breakdown(order, canceled, remaining).toRefund();
        if (!refund.isPayable()) {
            throw new IllegalStateException("남은 수량의 환불액이 0 이하입니다: orderId=" + order.id());
        }
        return new RemainingCancellation(remaining, refund.toMoney(), Money.of(settledRefund, currency));
    }

    /**
     * 환불이 기록되는 전이의 취소 정리 판정 (#684 PR-2) — 판정 입력은 주문 잠금 뒤 읽은 클레임 목록({@code stored})에서 이번 클레임을 빼고 그 갱신 결과
     * ({@code recorded}, 환불 완료)를 넣은 것이다. 갱신 전 목록으로 판정하면 이번 클레임이 열린 취소로 보여 {@link CancellationOutcome#STILL_REQUESTED} 가 나오고,
     * 열린 취소 없이 취소 요청에 멈춘 주문이 생긴다. 예외는 {@link #outcome(Order, List)} 와 같다.
     *
     * @param stored 이 주문의 클레임 전부 — 주문 잠금 뒤 읽은 것(이번 클레임은 갱신 전 값이어도 된다)
     * @param recorded 이번 클레임의 갱신 결과
     */
    public static CancellationOutcome outcome(Order order, List<Claim> stored, Claim recorded) {
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(recorded, "recorded");
        List<Claim> claims = new ArrayList<>(stored.size() + 1);
        stored.stream().filter(claim -> claim.id() != recorded.id()).forEach(claims::add);
        claims.add(recorded);
        return outcome(order, claims);
    }

    /**
     * 취소 클레임으로 본 주문의 다음 상태 (순수 판정, #684) — 환불이 기록된 취소({@link Claim#isSettledCancellation()})의 항목 수량 합이 모든 주문 항목의 수량과
     * 같으면 취소 완료, 아니고 열린 취소({@link Claim#isOpenCancellation()})가 있으면 취소 요청 유지, 그 밖(열린 취소 없이 일부만 환불)이면 결제 완료로 돌아간다.
     * 열린 취소 · 거절된 취소 · 반품 클레임은 합에 넣지 않는다. {@link Order#settleCancellation} 은 이 결과만 받는다.
     *
     * <p>데이터 이상은 판정하지 않는다(부르는 쪽이 fail-closed 로 주문을 취소 요청에 둔다) — 취소에 잡힌 수량(거절 밖 전부)이 주문 수량을 넘거나, 주문에 없는 항목을
     * 취소했거나, 열린 취소도 환불이 기록된 취소도 없을 때다(전부 거절된 취소는 취소 거절 전이 A4 몫이라 원래 상태를 이 판정이 알 수 없다).
     *
     * @param claims 이 주문의 클레임 전부
     * @throws IllegalArgumentException 다른 주문의 클레임
     * @throws IllegalStateException 위 데이터 이상
     */
    public static CancellationOutcome outcome(Order order, List<Claim> claims) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(claims, "claims");
        Map<Long, Integer> reserved = new HashMap<>();
        Map<Long, Integer> settled = new HashMap<>();
        boolean open = false;
        for (Claim claim : claims) {
            if (claim.orderId() != order.id()) {
                throw new IllegalArgumentException("다른 주문의 클레임입니다: claimId=" + claim.id());
            }
            if (!claim.reservesCancellationQuantity()) {
                continue;
            }
            for (ClaimItem item : claim.items()) {
                if (!order.itemIds().contains(item.orderItemId())) {
                    throw new IllegalStateException("주문에 없는 항목의 취소입니다: claimId=%d, orderItemId=%d".formatted(claim.id(), item.orderItemId()));
                }
                reserved.merge(item.orderItemId(), item.quantity(), Integer::sum);
                if (claim.isSettledCancellation()) {
                    settled.merge(item.orderItemId(), item.quantity(), Integer::sum);
                }
            }
            open |= claim.isOpenCancellation();
        }
        boolean all = true;
        for (int index = 0; index < order.itemIds().size(); index++) {
            long itemId = order.itemIds().get(index);
            int ordered = order.quote().lines().get(index).quantity();
            if (reserved.getOrDefault(itemId, 0) > ordered) {
                throw new IllegalStateException("취소에 잡힌 수량이 주문 수량을 넘습니다: orderItemId=" + itemId);
            }
            all &= settled.getOrDefault(itemId, 0) == ordered;
        }
        if (all) {
            return CancellationOutcome.CANCELED;
        }
        if (open) {
            return CancellationOutcome.STILL_REQUESTED;
        }
        if (settled.isEmpty()) {
            throw new IllegalStateException("환불이 기록된 취소도 열린 취소도 없는 주문은 취소 정리를 판정하지 않습니다: orderId=" + order.id());
        }
        return CancellationOutcome.PARTIALLY_CANCELED;
    }

    /** 남은 수량 {@code remaining}(줄 순서) 으로 받아야 할 금액. 원주문 그대로면 결제 총액, 전부 취소면 0 이다. */
    static long due(OrderQuote quote, int[] remaining) {
        return dueOf(quote, remaining).total();
    }

    /**
     * 남은 수량으로 받아야 할 금액을 상품 · 배송비로 나눈다 — {@code due = items + shipping} 이 모든 수량에서 성립하게 원주문의 배송비는 {@code 총액 − 상품 합계},
     * 전부 취소면 0 으로 둔다. 원주문은 다른 판정보다 먼저 본다(총액이 정본이다).
     */
    private static Due dueOf(OrderQuote quote, int[] remaining) {
        List<OrderQuoteLine> lines = quote.lines();
        boolean original = true;
        boolean empty = true;
        long items = 0;
        for (int index = 0; index < lines.size(); index++) {
            OrderQuoteLine line = lines.get(index);
            original &= remaining[index] == line.quantity();
            empty &= remaining[index] == 0;
            items = Math.addExact(items, Math.multiplyExact(line.unitPrice().amount(), (long) remaining[index]));
        }
        if (original) {
            return new Due(items, Math.subtractExact(quote.totalAmount().amount(), items));
        }
        if (empty) {
            return new Due(0, 0);
        }
        return new Due(items, shipping(quote.shippingFeeQuote(), items));
    }

    private static long shipping(ShippingFeeQuote fee, long items) {
        long snapshot = fee.shippingFee().amount();
        if (!fee.freeShippingApplied() || items >= fee.freeShippingThreshold().amount()) {
            return snapshot;
        }
        long recharge = fee.baseFee().amount();
        if (fee.scope() == ShippingScope.INTERNATIONAL) {
            recharge = Math.addExact(recharge, fee.weightFee().amount());
        }
        return Math.addExact(snapshot, recharge);
    }

    private static int[] originalQuantities(OrderQuote quote) {
        return quote.lines().stream().mapToInt(OrderQuoteLine::quantity).toArray();
    }

    /** 남은 수량으로 받아야 할 상품 금액 · 배송비 (최소 단위). */
    private record Due(long items, long shipping) {

        long total() {
            return Math.addExact(items, shipping);
        }
    }

    /**
     * 계산한 환불액 — 최소 단위 정수 + 통화. 0 이하일 수 있어 {@link Money} 가 아니다.
     *
     * @param amount 환불액(최소 단위). 0 이하면 재부과액이 취소 금액 이상이다
     */
    public record Refund(long amount, CurrencyCode currency) {

        public Refund {
            Objects.requireNonNull(currency, "currency");
        }

        /** 재부과액이 취소한 상품 금액보다 커 돌려줄 돈이 음수인가. 접수 판정은 {@link #isPayable()} 이다(0 도 접수하지 않는다). */
        public boolean isShortfall() {
            return amount < 0;
        }

        /** 돌려줄 돈이 있는가 — 0보다 크다. 0 이하면 부분 취소를 접수하지 않는다(#684 PR-4 409). */
        public boolean isPayable() {
            return amount > 0;
        }

        /** @throws IllegalStateException 음수 환불액 */
        public Money toMoney() {
            if (isShortfall()) {
                throw new IllegalStateException("음수 환불액은 금액으로 바꿀 수 없습니다: " + amount + " " + currency);
            }
            return Money.of(amount, currency);
        }
    }

    /**
     * 부분 취소 환불 내역 (#684) — 고객 응답 · 미리보기가 보여 주는 나눔이다. 환불액 {@link #amount()} 은 {@link #refund} 와 같다.
     *
     * @param itemsAmount 이번에 취소한 상품 금액(단가 × 수량 합, 0 이상)
     * @param shippingFeeRefund 돌려주는 배송비(0 이상) — 남은 주문의 배송비가 줄었을 때(마지막 수량까지 취소 등) 그 차이
     * @param shippingFeeRecharge 다시 받는 배송비(0 이상) — 남은 상품 합계가 무료배송 기준 아래로 내려가 배송비가 늘었을 때 그 차이. 배송비 환불과 함께 0보다 클 수 없다
     */
    public record RefundBreakdown(long itemsAmount, long shippingFeeRefund, long shippingFeeRecharge, CurrencyCode currency) {

        public RefundBreakdown {
            Objects.requireNonNull(currency, "currency");
            if (itemsAmount < 0 || shippingFeeRefund < 0 || shippingFeeRecharge < 0 || (shippingFeeRefund > 0 && shippingFeeRecharge > 0)) {
                throw new IllegalArgumentException("환불 내역은 0 이상이고 배송비 환불 · 재부과 중 하나만 있습니다: %d, %d, %d"
                    .formatted(itemsAmount, shippingFeeRefund, shippingFeeRecharge));
            }
        }

        /** 환불액 = 상품 금액 + 배송비 환불 − 배송비 재부과. 0 이하일 수 있다. */
        public long amount() {
            return Math.subtractExact(Math.addExact(itemsAmount, shippingFeeRefund), shippingFeeRecharge);
        }

        /** 돌려줄 돈이 있는가 — 0보다 크다. 0 이하면 부분 취소를 접수하지 않는다(#684 PR-4 409). */
        public boolean isPayable() {
            return amount() > 0;
        }

        public Refund toRefund() {
            return new Refund(amount(), currency);
        }
    }

    /**
     * 남은 수량 전부를 취소하는 전체 취소의 접수 값 (#684 PR-2, {@link #remaining}).
     *
     * @param quantities 이번에 취소할 주문 항목별 수량(주문 항목 아이디 → 1 이상) — 남은 수량 전부
     * @param refund 이번 환불액 {@code due(before)} — 0보다 크다
     * @param settledRefund 환불이 기록된 취소({@link Claim#isSettledCancellation()})의 환불액 합 — 선행 취소가 없으면 0
     */
    public record RemainingCancellation(Map<Long, Integer> quantities, Money refund, Money settledRefund) {

        public RemainingCancellation {
            quantities = Map.copyOf(Objects.requireNonNull(quantities, "quantities"));
            Objects.requireNonNull(refund, "refund");
            Objects.requireNonNull(settledRefund, "settledRefund");
        }
    }

    /** 취소 정리 결과 — {@link #outcome} 이 정하고 {@link Order#settleCancellation} 이 받는다. 주문을 이 셋 밖의 상태로 보낼 수 없다. */
    @Getter
    @RequiredArgsConstructor
    public enum CancellationOutcome {
        /** 열린 취소(환불 대기 · 환불 실패)가 남아 취소 요청에 머문다 — 출고하지 않는다. */
        STILL_REQUESTED(OrderStatus.CANCEL_REQUESTED),
        /** 모든 주문 수량의 취소 환불이 기록됐다. */
        CANCELED(OrderStatus.CANCELED),
        /** 열린 취소 없이 일부 수량만 환불됐다 — 결제 완료로 돌아가 남은 수량을 출고한다. */
        PARTIALLY_CANCELED(OrderStatus.PAID);

        private final OrderStatus orderStatus;
    }
}
