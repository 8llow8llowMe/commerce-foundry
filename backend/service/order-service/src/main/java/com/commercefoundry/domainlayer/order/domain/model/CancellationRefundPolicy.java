package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 결제 후 취소의 환불액 산식 (#16 PR-2, {@code order-flow-guide.md} §5 · §2 "클레임의 배송비 재부과는 주문 스냅숏으로") — 현재 배송비 정책이 아니라
 * 주문 시점 스냅숏({@link OrderQuote} · {@link ShippingFeeQuote})만 쓴다.
 *
 * <ul>
 *   <li>{@code refund = due(취소 전 남은 수량) − due(취소 뒤 남은 수량)}, {@code due(S) = items(S) + ship(S)}, {@code ship(∅) = 0}</li>
 *   <li>아무것도 취소하지 않은 원주문의 {@code due} 는 결제 총액이다 — 전체 취소는 총액 전액(배송비 포함)을 돌려준다</li>
 *   <li>원주문이 무료배송이 아니면 남은 주문의 배송비는 스냅숏 배송비 그대로다</li>
 *   <li>무료배송이었고 남은 상품 합계가 무료배송 기준 미만이면 스냅숏 배송비에 기본 배송비(해외는 + 무게 요금)를 다시 부과한다</li>
 *   <li>도서산간 추가 배송비는 스냅숏 배송비에 이미 들어 있어 따로 다시 부과하지 않는다</li>
 * </ul>
 *
 * <p>부분 취소를 순서대로 다 하면 환불 합은 결제 총액과 같다(망원 합). 소액 항목을 취소해 재부과액이 그 항목 금액보다 크면 결과가 음수다 —
 * 이번(PR-2a)은 계산만 하고 {@link Refund#isShortfall()} 로 드러낸다. 음수를 어떻게 다룰지(거절 · 0 처리)는 부분 취소 API(PR-2b)에서 정한다.
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
     * 부분 취소 환불액.
     *
     * @param alreadyCanceled 이미 취소된 주문 항목별 수량 (주문 항목 아이디 → 수량). 없으면 빈 맵
     * @param toCancel 이번에 취소할 주문 항목별 수량 (1 이상)
     * @throws IllegalArgumentException 주문에 없는 항목 · 0 이하 수량 · 원래 수량을 넘는 누적 취소
     */
    public static Refund refund(Order order, Map<Long, Integer> alreadyCanceled, Map<Long, Integer> toCancel) {
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
        return new Refund(Math.subtractExact(due(quote, before), due(quote, after)), quote.currency());
    }

    /** 남은 수량 {@code remaining}(줄 순서) 으로 받아야 할 금액. 원주문 그대로면 결제 총액, 전부 취소면 0 이다. */
    static long due(OrderQuote quote, int[] remaining) {
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
            return quote.totalAmount().amount();
        }
        if (empty) {
            return 0;
        }
        return Math.addExact(items, shipping(quote.shippingFeeQuote(), items));
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

    /**
     * 계산한 환불액 — 최소 단위 정수 + 통화. 음수일 수 있어 {@link Money} 가 아니다.
     *
     * @param amount 환불액(최소 단위). 음수면 재부과액이 취소 금액보다 크다
     */
    public record Refund(long amount, CurrencyCode currency) {

        public Refund {
            Objects.requireNonNull(currency, "currency");
        }

        /** 재부과액이 취소한 상품 금액보다 커 돌려줄 돈이 음수인가 — 처리 규칙은 PR-2b. */
        public boolean isShortfall() {
            return amount < 0;
        }

        /** @throws IllegalStateException 음수 환불액 */
        public Money toMoney() {
            if (isShortfall()) {
                throw new IllegalStateException("음수 환불액은 금액으로 바꿀 수 없습니다: " + amount + " " + currency);
            }
            return Money.of(amount, currency);
        }
    }
}
