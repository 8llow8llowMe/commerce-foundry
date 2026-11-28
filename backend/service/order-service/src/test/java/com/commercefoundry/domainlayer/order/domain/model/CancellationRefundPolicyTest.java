package com.commercefoundry.domainlayer.order.domain.model;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.internationalQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CancellationRefundPolicyTest {

    private static Order order(OrderQuote quote) {
        AtomicLong ids = new AtomicLong(100);
        List<Long> itemIds = quote.lines().stream().map(line -> ids.incrementAndGet()).toList();
        return new Order(1L, 7L, OrderStatus.PAID, quote, itemIds, address(quote.countryCode()), 99L, Instant.EPOCH, null, null, Instant.EPOCH, 3L);
    }

    private static long item(Order order, int line) {
        return order.itemIds().get(line);
    }

    private static OrderQuote quote(String country, ShippingFeeQuote fee, OrderQuoteLine... lines) {
        Money items = List.of(lines).stream().map(OrderQuoteLine::lineAmount).reduce(krw(0), Money::plus);
        return new OrderQuote(CurrencyCode.KRW, List.of(lines), items, fee, items.plus(fee.shippingFee()), country);
    }

    @Test
    @DisplayName("전체 취소는 결제 총액(배송비 포함) 전액이다 — 무료배송 · 유료배송 모두")
    void fullRefundIsTotal() {
        Order free = order(domesticQuote(quoteLine(1, 30_000, 1), quoteLine(2, 25_000, 1)));
        Order paidShipping = order(domesticQuote(quoteLine(1, 10_000, 2), quoteLine(2, 5_000, 1)));

        assertThat(CancellationRefundPolicy.fullRefund(free)).isEqualTo(krw(55_000));
        assertThat(CancellationRefundPolicy.fullRefund(paidShipping)).isEqualTo(krw(28_000));
        assertThat(CancellationRefundPolicy.refund(paidShipping, Map.of(), Map.of(item(paidShipping, 0), 2, item(paidShipping, 1), 1)).toMoney())
            .isEqualTo(krw(28_000));
    }

    @Test
    @DisplayName("무료배송 주문에서 남은 상품 합계가 기준 이상이면 취소한 상품 금액만 돌려준다")
    void partialAboveThreshold() {
        Order order = order(domesticQuote(quoteLine(1, 30_000, 2), quoteLine(2, 5_000, 1)));

        CancellationRefundPolicy.Refund refund = CancellationRefundPolicy.refund(order, Map.of(), Map.of(item(order, 1), 1));

        assertThat(refund.toMoney()).isEqualTo(krw(5_000));
        assertThat(refund.isShortfall()).isFalse();
    }

    @Test
    @DisplayName("무료배송 기준 미달이 되면 기본 배송비를 다시 부과한다 — 순서대로 다 취소하면 환불 합 = 결제 총액")
    void partialBelowThresholdRecharges() {
        Order order = order(domesticQuote(quoteLine(1, 30_000, 1), quoteLine(2, 25_000, 1)));

        long first = CancellationRefundPolicy.refund(order, Map.of(), Map.of(item(order, 1), 1)).amount();
        long second = CancellationRefundPolicy.refund(order, Map.of(item(order, 1), 1), Map.of(item(order, 0), 1)).amount();

        assertThat(first).as("55,000 − (30,000 + 3,000)").isEqualTo(22_000);
        assertThat(second).as("남은 33,000 전액").isEqualTo(33_000);
        assertThat(first + second).isEqualTo(order.quote().totalAmount().amount());
    }

    @Test
    @DisplayName("수량 단위로 여러 번 나눠 취소해도 환불 합은 결제 총액이다")
    void sequentialPartialSumEqualsTotal() {
        Order order = order(domesticQuote(quoteLine(1, 12_000, 3), quoteLine(2, 7_000, 2)));
        long a = item(order, 0);
        long b = item(order, 1);
        List<Map<Long, Integer>> steps = List.of(Map.of(b, 1), Map.of(a, 1), Map.of(a, 1, b, 1), Map.of(a, 1));

        long sum = 0;
        Map<Long, Integer> canceled = new java.util.HashMap<>();
        for (Map<Long, Integer> step : steps) {
            sum += CancellationRefundPolicy.refund(order, Map.copyOf(canceled), step).amount();
            step.forEach((id, quantity) -> canceled.merge(id, quantity, Integer::sum));
        }

        assertThat(order.quote().shippingFeeQuote().freeShippingApplied()).isTrue();
        assertThat(sum).isEqualTo(order.quote().totalAmount().amount()).isEqualTo(50_000);
    }

    @Test
    @DisplayName("해외 무료배송 주문이 기준 미달이 되면 기본료 + 무게 요금(스냅숏)을 다시 부과한다")
    void internationalRechargeIncludesWeightFee() {
        ShippingFeeQuote fee = new ShippingFeeQuote(krw(0), krw(5_000), true, krw(0), null, krw(100_000), 4L, ShippingScope.INTERNATIONAL, "북미",
            krw(20_000), 1_000);
        Order order = order(quote("US", fee, quoteLine(1, 60_000, 2)));

        long first = CancellationRefundPolicy.refund(order, Map.of(), Map.of(item(order, 0), 1)).amount();
        long second = CancellationRefundPolicy.refund(order, Map.of(item(order, 0), 1), Map.of(item(order, 0), 1)).amount();

        assertThat(first).as("120,000 − (60,000 + 5,000 + 20,000)").isEqualTo(35_000);
        assertThat(first + second).isEqualTo(120_000);
    }

    @Test
    @DisplayName("원주문이 무료배송이 아니면 스냅숏 배송비를 그대로 두고 상품 금액만 돌려준다")
    void nonFreeOrderKeepsSnapshotFee() {
        Order order = order(domesticQuote(quoteLine(1, 10_000, 2), quoteLine(2, 5_000, 1)));

        long first = CancellationRefundPolicy.refund(order, Map.of(), Map.of(item(order, 1), 1)).amount();
        long second = CancellationRefundPolicy.refund(order, Map.of(item(order, 1), 1), Map.of(item(order, 0), 2)).amount();

        assertThat(first).isEqualTo(5_000);
        assertThat(second).as("남은 20,000 + 배송비 3,000").isEqualTo(23_000);
        assertThat(first + second).isEqualTo(28_000);
    }

    @Test
    @DisplayName("도서산간 추가 배송비는 스냅숏에 있는 그대로이고 따로 다시 부과하지 않는다")
    void remoteAreaSurchargeIsNotRechargedTwice() {
        ShippingFeeQuote fee = new ShippingFeeQuote(krw(5_000), krw(3_000), true, krw(5_000), "제주", krw(50_000), 3L, ShippingScope.DOMESTIC,
            null, null, null);
        Order order = order(quote("KR", fee, quoteLine(1, 30_000, 1), quoteLine(2, 25_000, 1)));

        long refund = CancellationRefundPolicy.refund(order, Map.of(), Map.of(item(order, 1), 1)).amount();

        assertThat(CancellationRefundPolicy.fullRefund(order)).isEqualTo(krw(60_000));
        assertThat(refund).as("60,000 − (30,000 + 도서산간 5,000 + 기본 3,000)").isEqualTo(22_000);
    }

    @Test
    @DisplayName("소액 항목을 취소해 재부과액이 더 크면 음수 환불이다 — 계산은 하지만 금액으로 못 바꾸고 접수 대상이 아니다(0 이하 거절, #684)")
    void shortfallIsComputedButNotMoney() {
        Order order = order(domesticQuote(quoteLine(1, 49_000, 1), quoteLine(2, 2_000, 1)));

        CancellationRefundPolicy.Refund refund = CancellationRefundPolicy.refund(order, Map.of(), Map.of(item(order, 1), 1));

        assertThat(refund.amount()).as("51,000 − (49,000 + 3,000)").isEqualTo(-1_000);
        assertThat(refund.isShortfall()).isTrue();
        assertThat(refund.isPayable()).isFalse();
        assertThatThrownBy(refund::toMoney).isInstanceOf(IllegalStateException.class);
        assertThat(CancellationRefundPolicy.breakdown(order, Map.of(), Map.of(item(order, 1), 1)))
            .isEqualTo(new CancellationRefundPolicy.RefundBreakdown(2_000, 0, 3_000, CurrencyCode.KRW));
    }

    @Test
    @DisplayName("재부과액이 취소 금액과 같으면 환불 0 원이다 — 음수는 아니지만 돌려줄 돈이 없어 접수하지 않는다 (#684)")
    void zeroRefundIsNotPayable() {
        Order order = order(domesticQuote(quoteLine(1, 48_000, 1), quoteLine(2, 3_000, 1)));

        CancellationRefundPolicy.RefundBreakdown breakdown = CancellationRefundPolicy.breakdown(order, Map.of(), Map.of(item(order, 1), 1));

        assertThat(breakdown).isEqualTo(new CancellationRefundPolicy.RefundBreakdown(3_000, 0, 3_000, CurrencyCode.KRW));
        assertThat(breakdown.amount()).isZero();
        assertThat(breakdown.isPayable()).isFalse();
        assertThat(breakdown.toRefund().isShortfall()).isFalse();
        assertThat(breakdown.toRefund().isPayable()).isFalse();
    }

    @Test
    @DisplayName("내역 — 무료배송 기준을 지키면 취소한 상품 금액만이고 배송비 환불 · 재부과가 없다 (#684)")
    void breakdownAboveThreshold() {
        Order order = order(domesticQuote(quoteLine(1, 30_000, 2), quoteLine(2, 5_000, 1)));

        assertThat(CancellationRefundPolicy.breakdown(order, Map.of(), Map.of(item(order, 1), 1)))
            .isEqualTo(new CancellationRefundPolicy.RefundBreakdown(5_000, 0, 0, CurrencyCode.KRW));
    }

    @Test
    @DisplayName("내역 — 무료배송 기준 아래로 내려가면 기본 배송비를 다시 받고, 마지막 수량을 취소하면 그 배송비를 돌려준다 (#684)")
    void breakdownBelowThresholdRechargesThenRefundsShipping() {
        Order order = order(domesticQuote(quoteLine(1, 30_000, 1), quoteLine(2, 25_000, 1)));

        CancellationRefundPolicy.RefundBreakdown first = CancellationRefundPolicy.breakdown(order, Map.of(), Map.of(item(order, 1), 1));
        CancellationRefundPolicy.RefundBreakdown last = CancellationRefundPolicy.breakdown(order, Map.of(item(order, 1), 1), Map.of(item(order, 0), 1));

        assertThat(first).isEqualTo(new CancellationRefundPolicy.RefundBreakdown(25_000, 0, 3_000, CurrencyCode.KRW));
        assertThat(first.amount()).isEqualTo(22_000);
        assertThat(last).isEqualTo(new CancellationRefundPolicy.RefundBreakdown(30_000, 3_000, 0, CurrencyCode.KRW));
        assertThat(first.amount() + last.amount()).isEqualTo(55_000);
    }

    @Test
    @DisplayName("내역 — 전량 취소는 상품 금액 + 스냅숏 배송비 환불이다(유료배송 주문) (#684)")
    void breakdownFullCancellationRefundsShippingFee() {
        Order order = order(domesticQuote(quoteLine(1, 10_000, 2), quoteLine(2, 5_000, 1)));

        CancellationRefundPolicy.RefundBreakdown all = CancellationRefundPolicy.breakdown(order, Map.of(), Map.of(item(order, 0), 2, item(order, 1), 1));

        assertThat(all).isEqualTo(new CancellationRefundPolicy.RefundBreakdown(25_000, 3_000, 0, CurrencyCode.KRW));
        assertThat(all.toRefund().toMoney()).isEqualTo(CancellationRefundPolicy.fullRefund(order)).isEqualTo(krw(28_000));
    }

    @Test
    @DisplayName("내역 — 이미 재부과된 뒤의 추가 취소는 배송비 차이가 0 이라 상품 금액만이다 (#684)")
    void breakdownAfterRechargeHasNoShippingDelta() {
        Order order = order(domesticQuote(quoteLine(1, 30_000, 1), quoteLine(2, 25_000, 1), quoteLine(3, 10_000, 1)));
        long a = item(order, 0);
        long b = item(order, 1);
        long c = item(order, 2);

        CancellationRefundPolicy.RefundBreakdown first = CancellationRefundPolicy.breakdown(order, Map.of(), Map.of(b, 1));
        CancellationRefundPolicy.RefundBreakdown second = CancellationRefundPolicy.breakdown(order, Map.of(b, 1), Map.of(c, 1));
        CancellationRefundPolicy.RefundBreakdown last = CancellationRefundPolicy.breakdown(order, Map.of(b, 1, c, 1), Map.of(a, 1));

        assertThat(first).as("65,000 → 40,000 — 기준 미달 재부과").isEqualTo(new CancellationRefundPolicy.RefundBreakdown(25_000, 0, 3_000, CurrencyCode.KRW));
        assertThat(second).as("40,000 → 30,000 — 이미 재부과돼 차이 0").isEqualTo(new CancellationRefundPolicy.RefundBreakdown(10_000, 0, 0, CurrencyCode.KRW));
        assertThat(last).isEqualTo(new CancellationRefundPolicy.RefundBreakdown(30_000, 3_000, 0, CurrencyCode.KRW));
        assertThat(first.amount() + second.amount() + last.amount()).isEqualTo(order.quote().totalAmount().amount());
    }

    @Test
    @DisplayName("내역 — 해외 무료배송이 기준 미달이 되면 기본료 + 무게 요금(스냅숏)을 다시 받고, 마지막 수량에서 돌려준다 (#684)")
    void breakdownInternationalWeightFee() {
        Order order = order(quote("US", internationalFreeFee(), quoteLine(1, 60_000, 2)));

        CancellationRefundPolicy.RefundBreakdown first = CancellationRefundPolicy.breakdown(order, Map.of(), Map.of(item(order, 0), 1));
        CancellationRefundPolicy.RefundBreakdown last = CancellationRefundPolicy.breakdown(order, Map.of(item(order, 0), 1), Map.of(item(order, 0), 1));

        assertThat(first).isEqualTo(new CancellationRefundPolicy.RefundBreakdown(60_000, 0, 25_000, CurrencyCode.KRW));
        assertThat(last).isEqualTo(new CancellationRefundPolicy.RefundBreakdown(60_000, 25_000, 0, CurrencyCode.KRW));
        assertThat(first.amount() + last.amount()).isEqualTo(120_000);
    }

    @Test
    @DisplayName("내역 합(상품 + 배송비 환불 − 재부과)은 모든 누적 · 이번 취소 조합에서 환불액 · due(before) − due(after) 와 같다 — 국내 무료 · 유료 · 도서산간 · 해외 (#684)")
    void breakdownSumEqualsRefundForAllCombinations() {
        List<Order> orders = List.of(
            order(domesticQuote(quoteLine(1, 30_000, 2), quoteLine(2, 7_000, 2))),
            order(domesticQuote(quoteLine(1, 10_000, 2), quoteLine(2, 5_000, 1))),
            order(quote("KR", new ShippingFeeQuote(krw(5_000), krw(3_000), true, krw(5_000), "제주", krw(50_000), 3L, ShippingScope.DOMESTIC, null, null, null),
                quoteLine(1, 30_000, 1), quoteLine(2, 25_000, 1))),
            order(quote("US", internationalFreeFee(), quoteLine(1, 60_000, 2), quoteLine(2, 15_000, 1))),
            order(internationalQuote(quoteLine(1, 20_000, 2), quoteLine(2, 9_000, 1))));
        int checked = 0;
        for (Order order : orders) {
            int[] ordered = order.quote().lines().stream().mapToInt(OrderQuoteLine::quantity).toArray();
            for (int[] canceled : vectors(ordered)) {
                int[] remaining = subtract(ordered, canceled);
                for (int[] toCancel : vectors(remaining)) {
                    if (Arrays.stream(toCancel).sum() == 0) {
                        continue;
                    }
                    CancellationRefundPolicy.RefundBreakdown breakdown = CancellationRefundPolicy.breakdown(order, quantities(order, canceled), quantities(order, toCancel));
                    long expected = CancellationRefundPolicy.due(order.quote(), remaining) - CancellationRefundPolicy.due(order.quote(), subtract(remaining, toCancel));
                    assertThat(breakdown.itemsAmount() + breakdown.shippingFeeRefund() - breakdown.shippingFeeRecharge()).isEqualTo(breakdown.amount())
                        .isEqualTo(CancellationRefundPolicy.refund(order, quantities(order, canceled), quantities(order, toCancel)).amount()).isEqualTo(expected);
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(30);
    }

    @Test
    @DisplayName("내역 값은 0 이상이고 배송비 환불 · 재부과가 함께 있을 수 없다")
    void breakdownInvariants() {
        assertThatThrownBy(() -> new CancellationRefundPolicy.RefundBreakdown(-1, 0, 0, CurrencyCode.KRW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CancellationRefundPolicy.RefundBreakdown(1, -1, 0, CurrencyCode.KRW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CancellationRefundPolicy.RefundBreakdown(1, 0, -1, CurrencyCode.KRW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CancellationRefundPolicy.RefundBreakdown(1, 1, 1, CurrencyCode.KRW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CancellationRefundPolicy.RefundBreakdown(1, 0, 0, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("정리 판정 — 환불이 기록된 취소 수량이 전 주문 수량이면 취소 완료, 열린 취소 없이 일부면 결제 완료 복귀다 (#684)")
    void outcomeAllOrPartial() {
        Order order = order(domesticQuote(quoteLine(1, 30_000, 2), quoteLine(2, 5_000, 1)));
        long a = item(order, 0);
        long b = item(order, 1);

        assertThat(CancellationRefundPolicy.outcome(order, List.of(cancellation(1, order, Claim.Status.REFUNDED, Map.of(a, 1)))))
            .isEqualTo(CancellationRefundPolicy.CancellationOutcome.PARTIALLY_CANCELED);
        assertThat(CancellationRefundPolicy.outcome(order, List.of(cancellation(1, order, Claim.Status.REFUNDED, Map.of(a, 1)),
            cancellation(2, order, Claim.Status.COMPLETED, Map.of(a, 1, b, 1))))).isEqualTo(CancellationRefundPolicy.CancellationOutcome.CANCELED);
        for (Claim.Status settled : List.of(Claim.Status.REFUNDED, Claim.Status.RESTORATION_FAILED, Claim.Status.COMPLETED)) {
            assertThat(CancellationRefundPolicy.outcome(order, List.of(cancellation(3, order, settled, Map.of(a, 2, b, 1))))).as(settled.name())
                .isEqualTo(CancellationRefundPolicy.CancellationOutcome.CANCELED);
        }
        assertThat(order.requestCancellation().settleCancellation(CancellationRefundPolicy.outcome(order,
            List.of(cancellation(1, order, Claim.Status.COMPLETED, Map.of(b, 1))))).status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("정리 판정 — 열린 취소(환불 대기 · 환불 실패)는 합에 넣지 않고 취소 요청에 머물게 한다. 거절된 취소 · 반품은 합에서 뺀다 (#684)")
    void outcomeExcludesOpenRejectedAndReturns() {
        Order order = order(domesticQuote(quoteLine(1, 30_000, 2), quoteLine(2, 5_000, 1)));
        long a = item(order, 0);
        long b = item(order, 1);
        Claim settled = cancellation(1, order, Claim.Status.REFUNDED, Map.of(a, 1));

        for (Claim.Status open : List.of(Claim.Status.REQUESTED, Claim.Status.REFUND_FAILED)) {
            assertThat(CancellationRefundPolicy.outcome(order, List.of(settled, cancellation(2, order, open, Map.of(a, 1, b, 1))))).as(open.name())
                .isEqualTo(CancellationRefundPolicy.CancellationOutcome.STILL_REQUESTED);
            assertThat(CancellationRefundPolicy.outcome(order, List.of(cancellation(2, order, open, Map.of(a, 2, b, 1))))).as("열린 전체 취소만 — " + open)
                .isEqualTo(CancellationRefundPolicy.CancellationOutcome.STILL_REQUESTED);
        }
        assertThat(CancellationRefundPolicy.outcome(order, List.of(settled, cancellation(2, order, Claim.Status.REJECTED, Map.of(a, 2, b, 1)))))
            .as("거절된 취소는 수량을 잡지 않는다").isEqualTo(CancellationRefundPolicy.CancellationOutcome.PARTIALLY_CANCELED);
        assertThat(CancellationRefundPolicy.outcome(order, List.of(settled, returnClaim(3, order, Map.of(a, 2)))))
            .as("반품 클레임은 취소 합이 아니다").isEqualTo(CancellationRefundPolicy.CancellationOutcome.PARTIALLY_CANCELED);
    }

    @Test
    @DisplayName("정리 판정 — 취소에 잡힌 수량이 주문 수량을 넘거나 주문에 없는 항목 · 판정할 취소가 없으면 상태 예외, 다른 주문의 클레임은 인자 예외다 (#684)")
    void outcomeRejectsAnomalies() {
        Order order = order(domesticQuote(quoteLine(1, 30_000, 2), quoteLine(2, 5_000, 1)));
        long a = item(order, 0);
        long b = item(order, 1);

        assertThatThrownBy(() -> CancellationRefundPolicy.outcome(order, List.of(cancellation(1, order, Claim.Status.REFUNDED, Map.of(a, 2)),
            cancellation(2, order, Claim.Status.COMPLETED, Map.of(a, 1))))).as("정산된 수량 초과").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CancellationRefundPolicy.outcome(order, List.of(cancellation(1, order, Claim.Status.REFUNDED, Map.of(a, 2, b, 1)),
            cancellation(2, order, Claim.Status.REQUESTED, Map.of(b, 1))))).as("정산 + 열린 수량 초과").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CancellationRefundPolicy.outcome(order, List.of(cancellation(1, order, Claim.Status.REFUNDED, Map.of(999L, 1)))))
            .as("주문에 없는 항목").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CancellationRefundPolicy.outcome(order, List.of())).as("취소 없음").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CancellationRefundPolicy.outcome(order, List.of(cancellation(1, order, Claim.Status.REJECTED, Map.of(a, 1)))))
            .as("전부 거절").isInstanceOf(IllegalStateException.class);
        Claim foreign = new Claim(9, order.id() + 1, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.REFUNDED, krw(1_000), 77L, 0, Instant.EPOCH,
            null, List.of(new ClaimItem(91, a, 1, ClaimItem.Reason.OTHER)), Instant.EPOCH, 0L);
        assertThatThrownBy(() -> CancellationRefundPolicy.outcome(order, List.of(foreign))).isInstanceOf(IllegalArgumentException.class);
    }

    private static ShippingFeeQuote internationalFreeFee() {
        return new ShippingFeeQuote(krw(0), krw(5_000), true, krw(0), null, krw(100_000), 4L, ShippingScope.INTERNATIONAL, "북미", krw(20_000), 1_000);
    }

    /** 각 줄 0 ~ {@code max[i]} 의 모든 수량 조합. */
    private static List<int[]> vectors(int[] max) {
        List<int[]> result = new ArrayList<>();
        result.add(new int[max.length]);
        for (int index = 0; index < max.length; index++) {
            List<int[]> next = new ArrayList<>();
            for (int[] base : result) {
                for (int quantity = 0; quantity <= max[index]; quantity++) {
                    int[] copy = base.clone();
                    copy[index] = quantity;
                    next.add(copy);
                }
            }
            result = next;
        }
        return result;
    }

    private static int[] subtract(int[] left, int[] right) {
        int[] result = new int[left.length];
        for (int index = 0; index < left.length; index++) {
            result[index] = left[index] - right[index];
        }
        return result;
    }

    /** 0 이 아닌 줄만 담은 주문 항목 아이디 → 수량. */
    private static Map<Long, Integer> quantities(Order order, int[] perLine) {
        Map<Long, Integer> result = new HashMap<>();
        for (int index = 0; index < perLine.length; index++) {
            if (perLine[index] > 0) {
                result.put(item(order, index), perLine[index]);
            }
        }
        return result;
    }

    private static Claim cancellation(long id, Order order, Claim.Status status, Map<Long, Integer> quantities) {
        List<ClaimItem> items = quantities.entrySet().stream()
            .map(entry -> new ClaimItem(id * 100 + entry.getKey() % 100, entry.getKey(), entry.getValue(), ClaimItem.Reason.OTHER)).toList();
        return new Claim(id, order.id(), UUID.randomUUID(), Claim.Type.CANCELLATION, status, krw(1_000), 77L, 0,
            Claim.Type.CANCELLATION.isAwaitingAttempt(status) ? Instant.EPOCH : null, status.isFailed() ? "PAYMENT_016" : null, items, Instant.EPOCH, 0L);
    }

    private static Claim returnClaim(long id, Order order, Map<Long, Integer> quantities) {
        List<ClaimItem> items = quantities.entrySet().stream()
            .map(entry -> new ClaimItem(id * 100 + entry.getKey() % 100, entry.getKey(), entry.getValue(), ClaimItem.Reason.CHANGE_OF_MIND)).toList();
        return new Claim(id, order.id(), UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null, items, Instant.EPOCH, 0L);
    }

    @Test
    @DisplayName("주문에 없는 항목 · 0 이하 수량 · 주문 수량을 넘는 누적 취소는 계산하지 않는다")
    void invalidQuantities() {
        Order order = order(domesticQuote(quoteLine(1, 30_000, 2)));
        long a = item(order, 0);

        assertThatThrownBy(() -> CancellationRefundPolicy.refund(order, Map.of(), Map.of(999L, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CancellationRefundPolicy.refund(order, Map.of(), Map.of(a, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CancellationRefundPolicy.refund(order, Map.of(), Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CancellationRefundPolicy.refund(order, Map.of(a, 2), Map.of(a, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CancellationRefundPolicy.refund(order, Map.of(a, -1), Map.of(a, 1))).isInstanceOf(IllegalArgumentException.class);
    }
}
