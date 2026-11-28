package com.commercefoundry.domainlayer.order.domain.model;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.domain.model.CancellationRefundPolicy.CancellationOutcome;
import com.commercefoundry.domainlayer.order.domain.model.CancellationRefundPolicy.RemainingCancellation;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 남은 수량 전체 취소 산식과 환불 기록 전이의 정리 판정 입력 (#684 PR-2, {@link CancellationRefundPolicy#remaining} ·
 * {@link CancellationRefundPolicy#outcome(Order, List, Claim)}). 값은 모두 가짜다.
 *
 * <p>주문: A(항목 101) 40,000 × 1 · B(항목 102) 10,000 × 2, 상품 60,000 — 무료배송(기준 50,000, 기본 배송비 3,000), 결제 60,000.
 */
class CancellationRemainderPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");
    private static final long ORDER = 1L;
    private static final long ITEM_A = 101L;
    private static final long ITEM_B = 102L;
    private static final AtomicLong IDS = new AtomicLong(1_000);

    private static Order order() {
        Order placed = Order.place(ORDER, 7, domesticQuote(quoteLine(9001, 40_000, 1), quoteLine(9002, 10_000, 2)), new AtomicLong(100)::incrementAndGet,
            address("KR"), 99, NOW);
        return new Order(ORDER, 7, OrderStatus.PAID, placed.quote(), placed.itemIds(), placed.shippingAddress(), 99, placed.paymentDueAt(), null, null, NOW, 3L);
    }

    /** 저장된 취소 클레임 — 상태에 맞춰 다음 시각 · 실패 코드를 채운다. */
    private static Claim cancellation(long id, Claim.Status status, Map<Long, Integer> quantities, long refund) {
        return cancellation(id, ORDER, status, quantities, refund);
    }

    private static Claim cancellation(long id, long orderId, Claim.Status status, Map<Long, Integer> quantities, long refund) {
        List<ClaimItem> items = new ArrayList<>();
        quantities.forEach((itemId, quantity) -> items.add(new ClaimItem(IDS.incrementAndGet(), itemId, quantity, ClaimItem.Reason.OTHER)));
        return new Claim(id, orderId, UUID.randomUUID(), Claim.Type.CANCELLATION, status, krw(refund), 77L, 0,
            Claim.Type.CANCELLATION.isAwaitingAttempt(status) ? NOW : null, status.isFailed() ? "PAYMENT_019" : null, items, NOW, 1L);
    }

    @Test
    @DisplayName("선행 취소가 없으면 전 항목 · 전 수량 · 결제 총액이고 정산된 환불은 0 이다 — 지금의 전체 취소와 같다")
    void withoutPriorCancellationIsFullRefund() {
        RemainingCancellation remaining = CancellationRefundPolicy.remaining(order(), List.of());

        assertThat(remaining.quantities()).containsExactlyInAnyOrderEntriesOf(Map.of(ITEM_A, 1, ITEM_B, 2));
        assertThat(remaining.refund()).isEqualTo(CancellationRefundPolicy.fullRefund(order())).isEqualTo(krw(60_000));
        assertThat(remaining.settledRefund()).isEqualTo(krw(0));
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = {"REFUNDED", "RESTORATION_FAILED", "COMPLETED"})
    @DisplayName("환불이 기록된 부분 취소 뒤에는 남은 수량 전부와 due(before) — 정산된 환불 합 + 이번 환불 = 결제 총액(망원 합)")
    void settledPartialLeavesRemainder(Claim.Status settled) {
        Claim partial = cancellation(10, settled, Map.of(ITEM_A, 1), 37_000);

        RemainingCancellation remaining = CancellationRefundPolicy.remaining(order(), List.of(partial));

        // 남은 B × 2 = 20,000 은 무료배송 기준 아래라 배송비 3,000 을 다시 받는다 — 부분 취소의 환불 37,000 = 60,000 − 23,000
        assertThat(CancellationRefundPolicy.refund(order(), Map.of(), Map.of(ITEM_A, 1)).amount()).isEqualTo(37_000);
        assertThat(remaining.quantities()).containsExactlyEntriesOf(Map.of(ITEM_B, 2));
        assertThat(remaining.refund()).isEqualTo(krw(23_000));
        assertThat(remaining.settledRefund()).isEqualTo(krw(37_000));
        assertThat(remaining.settledRefund().plus(remaining.refund())).isEqualTo(order().quote().totalAmount());
    }

    @Test
    @DisplayName("거절된 취소와 반품 클레임은 남은 수량 · 정산된 환불에 넣지 않는다")
    void ignoresRejectedCancellationsAndReturns() {
        Claim rejected = cancellation(10, Claim.Status.REJECTED, Map.of(ITEM_A, 1), 37_000);
        Claim returned = new Claim(11, ORDER, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(IDS.incrementAndGet(), ITEM_A, 1, ClaimItem.Reason.DEFECTIVE)), NOW, 1L);

        RemainingCancellation remaining = CancellationRefundPolicy.remaining(order(), List.of(rejected, returned));

        assertThat(remaining.quantities()).containsExactlyInAnyOrderEntriesOf(Map.of(ITEM_A, 1, ITEM_B, 2));
        assertThat(remaining.refund()).isEqualTo(krw(60_000));
        assertThat(remaining.settledRefund()).isEqualTo(krw(0));
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = {"REQUESTED", "REFUND_FAILED"})
    @DisplayName("열린 취소가 있으면 계산하지 않는다(데이터 이상) — 주문당 열린 취소는 하나이고 그동안 주문은 취소 요청이다")
    void openCancellationIsDataAnomaly(Claim.Status open) {
        Claim partial = cancellation(10, open, Map.of(ITEM_A, 1), 37_000);

        assertThatThrownBy(() -> CancellationRefundPolicy.remaining(order(), List.of(partial))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("수량 초과 · 주문에 없는 항목 · 남은 수량 없음은 IllegalStateException, 다른 주문의 클레임은 IllegalArgumentException 이다")
    void otherDataAnomalies() {
        Order order = order();
        List<Claim> overQuantity = List.of(cancellation(10, Claim.Status.COMPLETED, Map.of(ITEM_A, 1), 37_000),
            cancellation(11, Claim.Status.COMPLETED, Map.of(ITEM_A, 1), 37_000));
        List<Claim> unknownItem = List.of(cancellation(12, Claim.Status.COMPLETED, Map.of(999L, 1), 1_000));
        List<Claim> nothingLeft = List.of(cancellation(13, Claim.Status.COMPLETED, Map.of(ITEM_A, 1, ITEM_B, 2), 60_000));

        for (List<Claim> claims : List.of(overQuantity, unknownItem, nothingLeft)) {
            assertThatThrownBy(() -> CancellationRefundPolicy.remaining(order, claims)).isInstanceOf(IllegalStateException.class);
        }
        Claim otherOrder = cancellation(14, 2L, Claim.Status.COMPLETED, Map.of(ITEM_A, 1), 37_000);
        assertThatThrownBy(() -> CancellationRefundPolicy.remaining(order, List.of(otherOrder))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정산된 취소의 환불 통화가 주문 통화와 다르면 계산하지 않는다(데이터 이상)")
    void settledRefundInOtherCurrencyIsDataAnomaly() {
        Claim settled = new Claim(10, ORDER, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.COMPLETED, Money.of(3_700, CurrencyCode.USD), 77L, 0, null,
            null, List.of(new ClaimItem(IDS.incrementAndGet(), ITEM_A, 1, ClaimItem.Reason.OTHER)), NOW, 1L);

        assertThatThrownBy(() -> CancellationRefundPolicy.remaining(order(), List.of(settled))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("남은 수량의 환불액(due(before))이 0 이하면 계산하지 않는다 — 0원 사은품만 남고 배송비도 0 인 주문")
    void nonPayableRemainderIsDataAnomaly() {
        // 6만 원 상품 + 0원 사은품, 기본 배송비 0 · 무료배송 — 상품을 먼저 취소하면 남은 사은품의 due 는 0 이다
        ShippingFeeQuote fee = new ShippingFeeQuote(krw(0), krw(0), true, krw(0), null, krw(50_000), 3L, ShippingScope.DOMESTIC, null, null, null);
        OrderQuote quote = new OrderQuote(CurrencyCode.KRW, List.of(quoteLine(9001, 60_000, 1), quoteLine(9003, 0, 1)), krw(60_000), fee, krw(60_000), "KR");
        Order placed = Order.place(ORDER, 7, quote, new AtomicLong(100)::incrementAndGet, address("KR"), 99, NOW);
        Order order = new Order(ORDER, 7, OrderStatus.PAID, quote, placed.itemIds(), placed.shippingAddress(), 99, placed.paymentDueAt(), null, null, NOW, 3L);
        Claim settled = cancellation(10, Claim.Status.COMPLETED, Map.of(ITEM_A, 1), 60_000);

        assertThat(CancellationRefundPolicy.refund(order, Map.of(ITEM_A, 1), Map.of(ITEM_B, 1)).isPayable()).isFalse();
        assertThatThrownBy(() -> CancellationRefundPolicy.remaining(order, List.of(settled))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("정리 판정 입력 — 목록의 이번 클레임(갱신 전 환불 대기)을 갱신 결과로 바꿔 넣는다. 바꾸지 않으면 STILL_REQUESTED 로 멈춘다")
    void outcomeReplacesStaleEntryWithRecordedClaim() {
        Claim stale = cancellation(10, Claim.Status.REQUESTED, Map.of(ITEM_A, 1), 37_000);
        Claim recorded = new Claim(stale.id(), stale.orderId(), stale.requestKey(), stale.type(), Claim.Status.REFUNDED, stale.expectedRefund(),
            stale.paymentId(), 0, NOW, null, stale.items(), stale.requestedAt(), stale.version());

        assertThat(CancellationRefundPolicy.outcome(order(), List.of(stale))).as("바꿔 넣지 않은 판정(회귀)").isEqualTo(CancellationOutcome.STILL_REQUESTED);
        assertThat(CancellationRefundPolicy.outcome(order(), List.of(stale), recorded)).isEqualTo(CancellationOutcome.PARTIALLY_CANCELED);
        assertThat(CancellationRefundPolicy.outcome(order(), List.of(), recorded)).as("목록에 없으면 더한다").isEqualTo(CancellationOutcome.PARTIALLY_CANCELED);
    }

    @Test
    @DisplayName("부분 취소 뒤 남은 수량 클레임의 환불이 기록되면 취소 완료다")
    void remainderRecordedSettlesToCanceled() {
        Claim settled = cancellation(10, Claim.Status.COMPLETED, Map.of(ITEM_A, 1), 37_000);
        Claim stale = cancellation(11, Claim.Status.REQUESTED, Map.of(ITEM_B, 2), 23_000);
        Claim recorded = new Claim(stale.id(), stale.orderId(), stale.requestKey(), stale.type(), Claim.Status.REFUNDED, stale.expectedRefund(),
            stale.paymentId(), 0, NOW, null, stale.items(), stale.requestedAt(), stale.version());

        assertThat(CancellationRefundPolicy.outcome(order(), List.of(stale, settled), recorded)).isEqualTo(CancellationOutcome.CANCELED);
        assertThatThrownBy(() -> CancellationRefundPolicy.outcome(order(), List.of(stale), cancellation(12, 2L, Claim.Status.REFUNDED, Map.of(ITEM_B, 2), 23_000)))
            .as("다른 주문의 갱신 결과").isInstanceOf(IllegalArgumentException.class);
    }
}
