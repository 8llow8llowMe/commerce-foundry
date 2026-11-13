package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 반품 환불액 산식과 반품 정리 판정 (#17 B1, {@code order-flow-guide.md} §5) — {@link CancellationRefundPolicy} 와 같이 주문 시점 스냅숏({@link OrderQuote} ·
 * {@link ShippingFeeQuote})만 쓰고, 현재 배송비 정책을 읽지 않는다. 순수 계산이며 승인 여부(0 이하 거부 등)는 부르는 쪽(B2)이 정한다.
 *
 * <p>{@code R = 남은 결제액 = 결제 총액 − 이미 정산한 반품의 정책 금액 합}({@link Settled#amount()}). 이번 반품 전 · 뒤에 남는 수량을 {@code before} ·
 * {@code after} 라 하면
 * <ul>
 *   <li><b>고객 귀책</b>(단순 변심) — {@code min(R, max(R − due(after), due(before) − due(after)))}. {@code due} 는 취소 산식과 같다 — 원주문 그대로면 결제
 *       총액, 전부 반품이면 0, 그 밖은 상품 금액 + 스냅숏 배송비(무료배송이었고 남은 상품이 기준 미만이면 기본 배송비 · 해외 무게 요금 재부과). 앞선 반품이
 *       모두 고객 귀책이면 {@code R = due(before)} 라 취소 산식 {@code due(before) − due(after)} 와 같다. <b>변심 전량 반품은 처음 배송비까지 돌려준다</b>
 *       (원주문 due = 결제 총액) — 반품 배송비(왕복 등)는 운영자가 승인 때 차감액으로 뺀다. 고객사별 예외 플래그는 두지 않는다</li>
 *   <li><b>판매자 귀책</b>(불량 · 오배송) — 반품 항목 금액. 남는 수량이 0 이면 남은 결제액 전부(남아 있던 배송비 포함)다. 재부과는 없다</li>
 * </ul>
 *
 * <p><b>판매자 귀책이 만든 재부과는 판매자가 진다</b>(사용자 결정 11-13) — 판매자 귀책 반품으로 무료배송 기준 아래로 내려가면 {@code R} 이
 * {@code due(before)} 보다 재부과액만큼 작다. {@code R − due(after)} 만 쓰면 그 재부과를 다음 변심 반품 고객이 떠안으므로, 변심 반품은 적어도 그 반품이 만든
 * 차이({@code due(before) − due(after)})를 돌려주고, {@code min(R, …)} 으로 남은 결제액을 넘지 않는다. 그래서 귀책이 섞인 순차 반품도 환불 합이 결제 총액을
 * 넘지 않고, 같은 귀책을 순서대로 다 하면 환불 합은 결제 총액이다. 환불액은 {@code 정책 금액 − 차감액} 이고 0 이하일 수 있다({@link Refund#isPayable()}) —
 * 소액 변심 반품의 재부과액이 상품 금액보다 크거나 차감액이 정책 금액 이상인 경우다.
 */
public final class ReturnRefundPolicy {

    private ReturnRefundPolicy() {
    }

    /**
     * 반품 환불액 (차감액 0). 귀책 기본값은 {@link Fault#defaultOf(Claim)} 이다.
     *
     * @param settled 이 반품을 대상으로 모은 정산 — {@link Settled#of(Order, List, Claim)}
     * @param returnClaim 이번에 승인할 반품 접수(REQUESTED) — 항목 수량이 반품 수량이다
     * @throws IllegalStateException 접수 상태가 아니거나 이미 정산에 넣은 반품 클레임
     */
    public static Refund refund(Order order, Settled settled, Claim returnClaim, Fault fault) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(settled, "settled");
        Objects.requireNonNull(returnClaim, "returnClaim");
        if (returnClaim.type() != Claim.Type.RETURN || returnClaim.orderId() != order.id()) {
            throw new IllegalArgumentException("같은 주문의 반품 클레임만 계산합니다: claimId=" + returnClaim.id());
        }
        if (returnClaim.status() != Claim.Status.REQUESTED || settled.claimIds().contains(returnClaim.id())) {
            throw new IllegalStateException("접수(REQUESTED) 반품만 환불액을 계산합니다 — 정산 · 진행한 반품은 다시 계산하지 않습니다: claimId=" + returnClaim.id());
        }
        Map<Long, Integer> toReturn = new HashMap<>();
        returnClaim.items().forEach(item -> toReturn.merge(item.orderItemId(), item.quantity(), Integer::sum));
        return refund(order, settled, toReturn, fault);
    }

    /**
     * 반품 환불액 (차감액 0).
     *
     * @param toReturn 이번에 반품할 주문 항목별 수량 (1 이상)
     * @throws IllegalArgumentException 주문에 없는 항목 · 0 이하 수량 · 주문 수량을 넘는 누적 반품 · 결제 총액을 넘는 정산액 · 다른 통화
     */
    public static Refund refund(Order order, Settled settled, Map<Long, Integer> toReturn, Fault fault) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(settled, "settled");
        Objects.requireNonNull(toReturn, "toReturn");
        Objects.requireNonNull(fault, "fault");
        OrderQuote quote = order.quote();
        if (toReturn.isEmpty() || toReturn.values().stream().anyMatch(quantity -> quantity == null || quantity < 1)) {
            throw new IllegalArgumentException("반품 수량은 1 이상이어야 합니다");
        }
        List<Long> itemIds = order.itemIds();
        if (!itemIds.containsAll(settled.quantities().keySet()) || !itemIds.containsAll(toReturn.keySet())) {
            throw new IllegalArgumentException("주문에 없는 항목입니다: orderId=" + order.id());
        }
        long total = quote.totalAmount().amount();
        if (settled.currency() != quote.currency() || settled.amount() > total) {
            throw new IllegalArgumentException("이미 정산한 반품 금액이 주문 통화 · 결제 총액과 맞지 않습니다: orderId=" + order.id());
        }
        int[] before = new int[itemIds.size()];
        int[] after = new int[itemIds.size()];
        long returnedItems = 0;
        boolean empty = true;
        for (int index = 0; index < after.length; index++) {
            long itemId = itemIds.get(index);
            int returning = toReturn.getOrDefault(itemId, 0);
            before[index] = quote.lines().get(index).quantity() - settled.quantities().getOrDefault(itemId, 0);
            after[index] = before[index] - returning;
            if (after[index] < 0) {
                throw new IllegalArgumentException("누적 반품 수량이 주문 수량을 넘습니다: orderItemId=" + itemId);
            }
            empty &= after[index] == 0;
            returnedItems = Math.addExact(returnedItems, Math.multiplyExact(quote.lines().get(index).unitPrice().amount(), (long) returning));
        }
        long remainingPaid = total - settled.amount();
        long amount = switch (fault) {
            case CUSTOMER -> customerAmount(quote, remainingPaid, before, after);
            case SELLER -> empty ? remainingPaid : returnedItems;
        };
        return new Refund(amount, 0, quote.currency());
    }

    /** 고객 귀책 정책 금액 — {@code min(R, max(R − due(after), due(before) − due(after)))}. 판매자 귀책이 만든 재부과를 다음 변심 반품에 넘기지 않는다. */
    private static long customerAmount(OrderQuote quote, long remainingPaid, int[] before, int[] after) {
        long dueAfter = CancellationRefundPolicy.due(quote, after);
        long byRemaining = Math.subtractExact(remainingPaid, dueAfter);
        long byThisReturn = Math.subtractExact(CancellationRefundPolicy.due(quote, before), dueAfter);
        return Math.min(remainingPaid, Math.max(byRemaining, byThisReturn));
    }

    /**
     * 반품 클레임으로 본 주문의 다음 상태 (순수 판정) — 정산된 반품({@link Claim#isSettledReturn()})의 항목 수량이 모든 주문 항목의 수량과 같으면 반품 완료,
     * 아니고 열린 반품({@link Claim#isOpenReturn()})이 하나라도 있으면 반품 요청 유지, 그 밖(일부만 환불 · 전부 거절)이면 배송 완료다.
     * {@link Order#settleReturn} 은 이 결과만 받는다.
     *
     * @param claims 이 주문의 클레임 전부
     * @throws IllegalArgumentException 다른 주문의 클레임
     * @throws IllegalStateException 거절이 아닌 취소 클레임(부분 취소 #684 전까지 fail-closed) · 정산된 반품 수량이 주문 수량을 넘는다(데이터 이상)
     */
    public static ReturnOutcome outcome(Order order, List<Claim> claims) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(claims, "claims");
        Map<Long, Integer> settled = new HashMap<>();
        boolean open = false;
        for (Claim claim : claims) {
            requireSameOrderWithoutLiveCancellation(order, claim);
            if (claim.isSettledReturn()) {
                claim.items().forEach(item -> settled.merge(item.orderItemId(), item.quantity(), Integer::sum));
            }
            open |= claim.isOpenReturn();
        }
        boolean all = true;
        for (int index = 0; index < order.itemIds().size(); index++) {
            int ordered = order.quote().lines().get(index).quantity();
            int returned = settled.getOrDefault(order.itemIds().get(index), 0);
            if (returned > ordered) {
                throw new IllegalStateException("환불된 반품 수량이 주문 수량을 넘습니다: orderItemId=" + order.itemIds().get(index));
            }
            all &= returned == ordered;
        }
        if (all) {
            return ReturnOutcome.RETURNED;
        }
        return open ? ReturnOutcome.STILL_REQUESTED : ReturnOutcome.DELIVERED;
    }

    /**
     * 다른 주문의 클레임은 인자 오류, 거절이 아닌 취소 클레임은 상태 오류다 — 반품 기준액 · 반품 완료 판정은 취소 환불 · 수량을 아직 합산하지 않는다.
     * 지금 전이표에서는 도달할 수 없다(취소는 출고 전, 반품은 배송 완료 뒤). 부분 취소(#684)가 들어오면 합산하도록 바꾼다.
     */
    private static void requireSameOrderWithoutLiveCancellation(Order order, Claim claim) {
        if (claim.orderId() != order.id()) {
            throw new IllegalArgumentException("다른 주문의 클레임입니다: claimId=" + claim.id());
        }
        if (claim.type() == Claim.Type.CANCELLATION && claim.status() != Claim.Status.REJECTED) {
            throw new IllegalStateException("거절이 아닌 취소 클레임이 있는 주문의 반품은 아직 계산하지 않습니다: claimId=" + claim.id());
        }
    }

    /** 귀책 — 환불 산식만 가른다. 사유는 기본값만 정하고, 운영자가 검수 뒤 덮어쓴다(브랜드 규칙을 고정하지 않는다). */
    public enum Fault {
        /** 고객 귀책(단순 변심 등) — 이 반품이 만든 남는 주문의 배송비 재부과를 받는다. 판매자 귀책이 만든 재부과는 받지 않는다. */
        CUSTOMER,
        /** 판매자 귀책(불량 · 오배송) — 반품 항목 금액을 돌려주고 재부과하지 않는다. */
        SELLER;

        /** 기본 귀책 — 모든 항목 사유가 상품 불량 · 오배송이면 판매자, 하나라도 단순 변심이면 고객이다. */
        public static Fault defaultOf(Claim returnClaim) {
            Objects.requireNonNull(returnClaim, "returnClaim");
            boolean seller = returnClaim.items().stream()
                .allMatch(item -> item.reason() == ClaimItem.Reason.DEFECTIVE || item.reason() == ClaimItem.Reason.WRONG_ITEM);
            return seller ? SELLER : CUSTOMER;
        }
    }

    /** 반품 정리 결과 — {@link #outcome} 이 정하고 {@link Order#settleReturn} 이 받는다. 주문을 이 셋 밖의 상태로 보낼 수 없다. */
    @Getter
    @RequiredArgsConstructor
    public enum ReturnOutcome {
        /** 열린 반품이 남아 반품 요청에 머문다. */
        STILL_REQUESTED(OrderStatus.RETURN_REQUESTED),
        /** 모든 주문 수량의 반품 환불이 기록됐다. */
        RETURNED(OrderStatus.RETURNED),
        /** 열린 반품 없이 일부만 환불됐거나 전부 거절됐다 — 배송 완료로 돌아간다. */
        DELIVERED(OrderStatus.DELIVERED);

        private final OrderStatus orderStatus;
    }

    /**
     * 이미 정산한 반품 — 정산된({@link Claim#isSettledReturn()}) 반품 클레임의 항목별 수량 합과 정책 금액 합(환불액 + 차감액)이다.
     *
     * @param quantities 주문 항목 아이디 → 반품 수량 (0 이상)
     * @param amount 정책 금액 합(최소 단위, 0 이상) — 차감액도 판매자가 가져간 정산분이라 더한다
     * @param claimIds 정산에 넣은 반품 클레임 아이디 — 같은 클레임을 다시 계산하지 않게 한다
     */
    public record Settled(Map<Long, Integer> quantities, long amount, CurrencyCode currency, Set<Long> claimIds) {

        public Settled {
            quantities = Map.copyOf(Objects.requireNonNull(quantities, "quantities"));
            claimIds = Set.copyOf(Objects.requireNonNull(claimIds, "claimIds"));
            Objects.requireNonNull(currency, "currency");
            if (amount < 0 || quantities.values().stream().anyMatch(quantity -> quantity < 0)) {
                throw new IllegalArgumentException("정산한 반품 수량 · 금액은 0 이상이어야 합니다");
            }
        }

        /** 클레임 아이디 없이 수량 · 금액만으로 만든다(산식 단위 계산용). */
        public Settled(Map<Long, Integer> quantities, long amount, CurrencyCode currency) {
            this(quantities, amount, currency, Set.of());
        }

        /** 정산한 반품이 없다. */
        public static Settled none(Order order) {
            return new Settled(Map.of(), 0, Objects.requireNonNull(order, "order").quote().currency());
        }

        /**
         * 이 주문의 클레임에서 {@code target} 반품을 승인하기 위한 정산을 모은다 — 정산된 반품({@link Claim#isSettledReturn()})만 더하고, 다른 접수 반품 ·
         * 거절 반품은 금액에 영향이 없어 건너뛴다.
         *
         * <p>fail-closed: 승인 뒤 환불이 끝나지 않은 다른 반품({@link Claim#isReturnRefundInFlight()} — APPROVED · REFUND_FAILED)이 있으면 계산하지 않는다.
         * 그 반품의 정책 금액이 남은 결제액에서 아직 빠지지 않아, 겹쳐 승인하면 상점 손실(재부과 누락)이나 고객 과소 환불(전량인데 처음 배송비 누락)이 난다.
         * 부르는 쪽(B2)은 이 예외를 409 로 바꾸고, 주문을 FOR UPDATE 로 잠근 뒤 클레임 목록을 읽는다. 거절이 아닌 취소 클레임도 거부한다.
         *
         * @param target 이번에 승인할 반품 접수(REQUESTED). 목록에 같은 아이디가 있으면 그 행도 REQUESTED 여야 한다(진행 · 정산한 반품 재계산 거부)
         * @throws IllegalArgumentException 다른 주문의 클레임 · 반품이 아닌 대상 · 다른 통화
         * @throws IllegalStateException 대상이 접수 상태가 아님 · 진행 중인 다른 반품 · 거절이 아닌 취소 클레임
         */
        public static Settled of(Order order, List<Claim> claims, Claim target) {
            Objects.requireNonNull(order, "order");
            Objects.requireNonNull(claims, "claims");
            Objects.requireNonNull(target, "target");
            if (target.type() != Claim.Type.RETURN || target.orderId() != order.id()) {
                throw new IllegalArgumentException("같은 주문의 반품 클레임만 정산 대상입니다: claimId=" + target.id());
            }
            requireRequested(target);
            CurrencyCode currency = order.quote().currency();
            Map<Long, Integer> quantities = new HashMap<>();
            Set<Long> claimIds = new HashSet<>();
            long amount = 0;
            for (Claim claim : claims) {
                requireSameOrderWithoutLiveCancellation(order, claim);
                if (claim.id() == target.id()) {
                    requireRequested(claim);
                    continue;
                }
                if (claim.isReturnRefundInFlight()) {
                    throw new IllegalStateException("환불이 끝나지 않은 다른 반품이 있어 환불액을 계산하지 않습니다: claimId=%d, status=%s"
                        .formatted(claim.id(), claim.status()));
                }
                if (!claim.isSettledReturn()) {
                    continue;
                }
                Money gross = claim.expectedRefund().plus(claim.approval().deduction());
                if (gross.currency() != currency) {
                    throw new IllegalArgumentException("주문 통화와 다른 반품 환불입니다: claimId=" + claim.id());
                }
                amount = Math.addExact(amount, gross.amount());
                claimIds.add(claim.id());
                claim.items().forEach(item -> quantities.merge(item.orderItemId(), item.quantity(), Integer::sum));
            }
            return new Settled(quantities, amount, currency, claimIds);
        }

        private static void requireRequested(Claim claim) {
            if (claim.status() != Claim.Status.REQUESTED) {
                throw new IllegalStateException("승인할 반품은 접수(REQUESTED) 상태여야 합니다: claimId=%d, status=%s".formatted(claim.id(), claim.status()));
            }
        }
    }

    /**
     * 계산한 반품 환불 — 최소 단위 정수 + 통화. 환불액은 {@code policyAmount − deduction} 이고 0 이하일 수 있어 {@link Money} 가 아니다.
     * 반품 승인({@link Claim#approveReturn})은 이 값 하나에서 환불액 · 차감액 · 통화를 꺼낸다.
     *
     * @param policyAmount 정책 금액(최소 단위). 음수면 재부과액이 반품 상품 금액보다 크다
     * @param deduction 운영자 차감액(최소 단위, 0 이상) — 반품 배송비 · 훼손 등
     */
    public record Refund(long policyAmount, long deduction, CurrencyCode currency) {

        public Refund {
            Objects.requireNonNull(currency, "currency");
            if (deduction < 0) {
                throw new IllegalArgumentException("차감액은 0 이상이어야 합니다: " + deduction);
            }
        }

        /** 실제로 돌려줄 금액 = 정책 금액 − 차감액. */
        public long amount() {
            return Math.subtractExact(policyAmount, deduction);
        }

        /** 운영자 차감액을 적용한다 — 기존 차감액을 바꾼다. */
        public Refund withDeduction(Money deduction) {
            Objects.requireNonNull(deduction, "deduction");
            if (deduction.currency() != currency) {
                throw new IllegalArgumentException("차감액 통화가 환불 통화와 다릅니다: " + deduction.currency());
            }
            return new Refund(policyAmount, deduction.amount(), currency);
        }

        /** 0보다 큰 환불인가 — 아니면 승인을 거부한다(B2, 409). 0 원 반품 승인을 두지 않는다. */
        public boolean isPayable() {
            return amount() > 0;
        }

        /** 차감액을 금액으로 — 승인 기록({@link Claim.ReturnApproval})에 싣는다. */
        public Money deductionMoney() {
            return Money.of(deduction, currency);
        }

        /** @throws IllegalStateException 0 이하 환불액 — 반품 승인이 이것으로 0 이하 환불을 거부한다 */
        public Money toMoney() {
            if (!isPayable()) {
                throw new IllegalStateException("0 이하 환불액은 금액으로 바꿀 수 없습니다: " + amount() + " " + currency);
            }
            return Money.of(amount(), currency);
        }
    }
}
