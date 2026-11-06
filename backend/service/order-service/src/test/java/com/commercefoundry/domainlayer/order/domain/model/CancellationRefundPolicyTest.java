package com.commercefoundry.domainlayer.order.domain.model;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    @DisplayName("소액 항목을 취소해 재부과액이 더 크면 음수 환불이다 — 지금은 계산만 하고(금액으로 못 바꾼다) 처리는 PR-2b 에서 정한다")
    void shortfallIsComputedButNotMoney() {
        Order order = order(domesticQuote(quoteLine(1, 49_000, 1), quoteLine(2, 2_000, 1)));

        CancellationRefundPolicy.Refund refund = CancellationRefundPolicy.refund(order, Map.of(), Map.of(item(order, 1), 1));

        assertThat(refund.amount()).as("51,000 − (49,000 + 3,000)").isEqualTo(-1_000);
        assertThat(refund.isShortfall()).isTrue();
        assertThatThrownBy(refund::toMoney).isInstanceOf(IllegalStateException.class);
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
