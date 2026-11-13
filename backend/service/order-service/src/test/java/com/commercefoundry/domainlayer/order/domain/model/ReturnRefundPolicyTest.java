package com.commercefoundry.domainlayer.order.domain.model;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy.Fault;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy.Refund;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy.ReturnOutcome;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy.Settled;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 반품 환불 산식 (#17 B1) — 주문 스냅숏만 쓰는 순수 계산. 금액 · 아이디는 모두 가짜다. */
class ReturnRefundPolicyTest {
    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");

    private static Order order(OrderQuote quote) {
        AtomicLong ids = new AtomicLong(100);
        List<Long> itemIds = quote.lines().stream().map(line -> ids.incrementAndGet()).toList();
        return new Order(1L, 7L, OrderStatus.RETURN_REQUESTED, quote, itemIds, address(quote.countryCode()), 99L, Instant.EPOCH, NOW, NOW, Instant.EPOCH, 3L);
    }

    private static long item(Order order, int line) {
        return order.itemIds().get(line);
    }

    private static Refund refund(Order order, Settled settled, Map<Long, Integer> toReturn, Fault fault) {
        return ReturnRefundPolicy.refund(order, settled, toReturn, fault);
    }

    @Test
    @DisplayName("변심 전량 반품은 결제 총액(처음 배송비 포함)이다 — 반품 배송비는 차감액으로 뺀다 (사용자 결정: 산식 유지)")
    void customerFullReturnIncludesOriginalShipping() {
        Order paidShipping = order(domesticQuote(quoteLine(1, 10_000, 2), quoteLine(2, 5_000, 1)));
        Order free = order(domesticQuote(quoteLine(1, 30_000, 1), quoteLine(2, 25_000, 1)));

        Refund paid = refund(paidShipping, Settled.none(paidShipping), Map.of(item(paidShipping, 0), 2, item(paidShipping, 1), 1), Fault.CUSTOMER);
        Refund freeAll = refund(free, Settled.none(free), Map.of(item(free, 0), 1, item(free, 1), 1), Fault.CUSTOMER);

        assertThat(paid.policyAmount()).as("25,000 + 배송비 3,000").isEqualTo(28_000);
        assertThat(paid.withDeduction(krw(6_000)).amount()).as("왕복 반품 배송비 6,000 차감").isEqualTo(22_000);
        assertThat(freeAll.policyAmount()).isEqualTo(55_000);
    }

    @Test
    @DisplayName("변심 부분 반품은 취소 산식과 같다 — 무료배송 기준 미달이면 기본 배송비를 다시 받는다")
    void customerPartialMatchesCancellationFormula() {
        Order order = order(domesticQuote(quoteLine(1, 30_000, 1), quoteLine(2, 25_000, 1)));
        Map<Long, Integer> toReturn = Map.of(item(order, 1), 1);

        Refund refund = refund(order, Settled.none(order), toReturn, Fault.CUSTOMER);

        assertThat(refund.policyAmount()).as("55,000 − (30,000 + 3,000)").isEqualTo(22_000)
            .isEqualTo(CancellationRefundPolicy.refund(order, Map.of(), toReturn).amount());
    }

    @Test
    @DisplayName("판매자 귀책 부분 반품은 항목 금액만 — 재부과하지 않는다. 마지막 반품이면 남은 결제액(남은 배송비 포함) 전부다")
    void sellerFaultRefundsItemsAndRemainingShippingAtTheEnd() {
        Order order = order(domesticQuote(quoteLine(1, 10_000, 2), quoteLine(2, 5_000, 1)));
        long a = item(order, 0);
        long b = item(order, 1);

        Refund first = refund(order, Settled.none(order), Map.of(b, 1), Fault.SELLER);
        Refund last = refund(order, new Settled(Map.of(b, 1), first.policyAmount(), CurrencyCode.KRW), Map.of(a, 2), Fault.SELLER);

        assertThat(first.policyAmount()).isEqualTo(5_000);
        assertThat(last.policyAmount()).as("20,000 + 배송비 3,000").isEqualTo(23_000);
        assertThat(first.policyAmount() + last.policyAmount()).isEqualTo(order.quote().totalAmount().amount());
        Order free = order(domesticQuote(quoteLine(1, 30_000, 1), quoteLine(2, 25_000, 1)));
        assertThat(refund(free, Settled.none(free), Map.of(item(free, 1), 1), Fault.SELLER).policyAmount()).as("기준 미달이어도 재부과 없음").isEqualTo(25_000);
    }

    @Test
    @DisplayName("같은 귀책으로 순서대로 다 반품하면 환불 합은 결제 총액이다 — 변심 · 판매자 각각")
    void sequentialReturnsSumToTotal() {
        Order order = order(domesticQuote(quoteLine(1, 12_000, 3), quoteLine(2, 7_000, 2)));
        long a = item(order, 0);
        long b = item(order, 1);
        List<Map<Long, Integer>> steps = List.of(Map.of(b, 1), Map.of(a, 1), Map.of(a, 1, b, 1), Map.of(a, 1));

        for (Fault fault : Fault.values()) {
            assertThat(sumOf(order, steps, List.of(fault, fault, fault, fault))).as(fault.name()).isEqualTo(50_000);
        }
        assertThat(order.quote().shippingFeeQuote().freeShippingApplied()).isTrue();
    }

    @Test
    @DisplayName("귀책이 섞여도 환불 합은 결제 총액을 넘지 않는다 — 판매자 귀책 뒤 변심 반품에 고객이 내지 않은 재부과를 돌려주지 않는다")
    void mixedFaultNeverOverRefunds() {
        Order order = order(domesticQuote(quoteLine(1, 30_000, 1), quoteLine(2, 25_000, 1)));
        long a = item(order, 0);
        long b = item(order, 1);

        long sellerThenCustomer = sumOf(order, List.of(Map.of(a, 1), Map.of(b, 1)), List.of(Fault.SELLER, Fault.CUSTOMER));
        long customerThenSeller = sumOf(order, List.of(Map.of(a, 1), Map.of(b, 1)), List.of(Fault.CUSTOMER, Fault.SELLER));

        assertThat(sellerThenCustomer).isEqualTo(55_000);
        assertThat(customerThenSeller).isEqualTo(55_000);
        Refund customerAfterSeller = refund(order, new Settled(Map.of(a, 1), 30_000, CurrencyCode.KRW), Map.of(b, 1), Fault.CUSTOMER);
        assertThat(customerAfterSeller.policyAmount()).as("남은 결제액 25,000 전부 — due(b) 28,000 이 아니다")
            .isEqualTo(25_000).isLessThan(CancellationRefundPolicy.refund(order, Map.of(a, 1), Map.of(b, 1)).amount());
    }

    @Test
    @DisplayName("해외 무료배송이 기준 미달이 되면 기본료 + 무게 요금을 다시 받는다")
    void internationalRecharge() {
        ShippingFeeQuote fee = new ShippingFeeQuote(krw(0), krw(5_000), true, krw(0), null, krw(100_000), 4L, ShippingScope.INTERNATIONAL, "북미",
            krw(20_000), 1_000);
        Order order = order(quote(CurrencyCode.KRW, "US", fee, quoteLine(1, 60_000, 2)));

        assertThat(refund(order, Settled.none(order), Map.of(item(order, 0), 1), Fault.CUSTOMER).policyAmount())
            .as("120,000 − (60,000 + 5,000 + 20,000)").isEqualTo(35_000);
    }

    @Test
    @DisplayName("USD 주문은 센트 단위 그대로 계산하고 통화를 싣는다 — 다른 통화 차감 · 정산은 받지 않는다")
    void usdOrder() {
        ShippingFeeQuote fee = new ShippingFeeQuote(usd(2_500), usd(500), false, usd(0), null, null, 4L, ShippingScope.INTERNATIONAL, "북미", usd(2_000), 1_000);
        OrderQuoteLine line = OrderQuoteLine.of(1, 7001, "상품1", "옵션1", usd(4_550), 2, OrderCustomOptions.empty(), false, 500, "6211420000");
        Order order = order(quote(CurrencyCode.USD, "US", fee, line));

        Refund all = refund(order, Settled.none(order), Map.of(item(order, 0), 2), Fault.CUSTOMER);
        Refund one = refund(order, Settled.none(order), Map.of(item(order, 0), 1), Fault.CUSTOMER);

        assertThat(all.currency()).isEqualTo(CurrencyCode.USD);
        assertThat(all.policyAmount()).as("9,100 + 2,500 센트").isEqualTo(11_600);
        assertThat(one.policyAmount()).as("유료배송 주문은 배송비 그대로 — 항목 금액만").isEqualTo(4_550);
        assertThat(one.withDeduction(usd(1_000)).toMoney()).isEqualTo(usd(3_550));
        assertThatThrownBy(() -> one.withDeduction(krw(1_000))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refund(order, new Settled(Map.of(), 0, CurrencyCode.KRW), Map.of(item(order, 0), 1), Fault.CUSTOMER))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("차감액 경계 — 정책 금액 − 1 이면 1 원, 같으면 0 원(승인 거부 대상), 넘으면 음수. 0 이하는 금액으로 바꾸지 않는다")
    void deductionBoundaries() {
        Order order = order(domesticQuote(quoteLine(1, 10_000, 2), quoteLine(2, 5_000, 1)));
        Refund refund = refund(order, Settled.none(order), Map.of(item(order, 1), 1), Fault.CUSTOMER);

        assertThat(refund.withDeduction(krw(0)).toMoney()).isEqualTo(krw(5_000));
        assertThat(refund.withDeduction(krw(4_999)).isPayable()).isTrue();
        assertThat(refund.withDeduction(krw(4_999)).toMoney()).isEqualTo(krw(1));
        assertThat(refund.withDeduction(krw(5_000)).amount()).isZero();
        assertThat(refund.withDeduction(krw(5_000)).isPayable()).isFalse();
        assertThat(refund.withDeduction(krw(5_001)).amount()).isEqualTo(-1);
        assertThat(refund.withDeduction(krw(3_000)).deductionMoney()).isEqualTo(krw(3_000));
        assertThatThrownBy(() -> refund.withDeduction(krw(5_000)).toMoney()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Refund(5_000, -1, CurrencyCode.KRW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("소액 변심 반품의 재부과가 더 크면 정책 금액이 음수다 — 계산만 하고 승인 거부는 B2 다")
    void shortfallIsExposed() {
        Order order = order(domesticQuote(quoteLine(1, 49_000, 1), quoteLine(2, 2_000, 1)));

        Refund refund = refund(order, Settled.none(order), Map.of(item(order, 1), 1), Fault.CUSTOMER);

        assertThat(refund.policyAmount()).as("51,000 − (49,000 + 3,000)").isEqualTo(-1_000);
        assertThat(refund.isPayable()).isFalse();
        assertThatThrownBy(refund::toMoney).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("기본 귀책 — 항목 사유가 모두 불량 · 오배송이면 판매자, 단순 변심이 하나라도 있으면 고객. 클레임으로 계산하면 그 항목 수량을 쓴다")
    void defaultFaultAndClaimOverload() {
        Order order = order(domesticQuote(quoteLine(1, 10_000, 2), quoteLine(2, 5_000, 1)));
        Claim seller = returnClaim(order, List.of(new ClaimItem(11, item(order, 0), 1, ClaimItem.Reason.DEFECTIVE),
            new ClaimItem(12, item(order, 1), 1, ClaimItem.Reason.WRONG_ITEM)));
        Claim mixed = returnClaim(order, List.of(new ClaimItem(11, item(order, 0), 1, ClaimItem.Reason.DEFECTIVE),
            new ClaimItem(12, item(order, 1), 1, ClaimItem.Reason.CHANGE_OF_MIND)));

        assertThat(Fault.defaultOf(seller)).isEqualTo(Fault.SELLER);
        assertThat(Fault.defaultOf(mixed)).isEqualTo(Fault.CUSTOMER);
        assertThat(ReturnRefundPolicy.refund(order, Settled.none(order), seller, Fault.defaultOf(seller)).policyAmount()).isEqualTo(15_000);
        assertThat(ReturnRefundPolicy.refund(order, Settled.none(order), seller, Fault.CUSTOMER).policyAmount()).as("운영자 덮어쓰기").isEqualTo(15_000);
        Claim foreign = new Claim(9, order.id() + 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null, seller.items(), NOW, 0L);
        assertThatThrownBy(() -> ReturnRefundPolicy.refund(order, Settled.none(order), foreign, Fault.SELLER)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이미 정산한 반품은 정산된 반품만 — 정책 금액 합은 환불액 + 차감액이고, 다른 접수 반품 · 거절 반품 · 거절된 취소는 넣지 않는다")
    void settledFromClaims() {
        Order order = order(domesticQuote(quoteLine(1, 10_000, 2), quoteLine(2, 5_000, 1)));
        long a = item(order, 0);
        long b = item(order, 1);
        Claim target = claim(order, 20, Claim.Status.REQUESTED, b, 1, 0, 0);
        List<Claim> claims = new ArrayList<>();
        claims.add(claim(order, 1, Claim.Status.COMPLETED, a, 1, 7_000, 3_000));
        claims.add(claim(order, 2, Claim.Status.RESTORATION_FAILED, b, 1, 5_000, 0));
        claims.add(claim(order, 3, Claim.Status.REFUNDED, a, 1, 1_000, 500));
        claims.add(claim(order, 4, Claim.Status.REQUESTED, a, 1, 0, 0));
        claims.add(claim(order, 5, Claim.Status.REJECTED, a, 1, 0, 0));
        claims.add(target);
        claims.add(new Claim(31, order.id(), UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.REJECTED, krw(28_000), 77L, 0, null, null,
            List.of(new ClaimItem(311, a, 2, ClaimItem.Reason.OTHER)), NOW, 0L));

        Settled settled = Settled.of(order, claims, target);

        assertThat(settled.quantities()).containsExactlyInAnyOrderEntriesOf(Map.of(a, 2, b, 1));
        assertThat(settled.amount()).as("(7,000+3,000) + 5,000 + (1,000+500)").isEqualTo(16_500);
        assertThat(settled.currency()).isEqualTo(CurrencyCode.KRW);
        assertThat(settled.claimIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
        Claim foreign = new Claim(40, order.id() + 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(401, a, 1, ClaimItem.Reason.DEFECTIVE)), NOW, 0L);
        assertThatThrownBy(() -> Settled.of(order, List.of(foreign), target)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Settled.of(order, List.of(), foreign)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("예 A (결제 HIGH 1) — 앞 반품이 승인 · 환불 대기(APPROVED)면 다음 반품 환불액을 계산하지 않는다. 앞 반품이 정산된 뒤에는 순차 산식 27,000 이다")
    void inFlightApprovedReturnBlocksNextRefund() {
        Order order = order(domesticQuote(quoteLine(1, 30_000, 1), quoteLine(2, 30_000, 1), quoteLine(3, 30_000, 1)));
        Claim first = claim(order, 1, Claim.Status.APPROVED, item(order, 0), 1, 30_000, 0);
        Claim second = claim(order, 2, Claim.Status.REQUESTED, item(order, 1), 1, 0, 0);

        assertThatThrownBy(() -> Settled.of(order, List.of(first, second), second)).as("겹쳐 승인하면 30,000 — 재부과 3,000 누락")
            .isInstanceOf(IllegalStateException.class);

        Claim firstSettled = claim(order, 1, Claim.Status.COMPLETED, item(order, 0), 1, 30_000, 0);
        Settled settled = Settled.of(order, List.of(firstSettled, second), second);
        assertThat(ReturnRefundPolicy.refund(order, settled, second, Fault.CUSTOMER).policyAmount()).as("60,000 − (30,000 + 3,000)").isEqualTo(27_000);
    }

    @Test
    @DisplayName("예 B (결제 HIGH 1) — 앞 반품이 환불 실패(REFUND_FAILED, 실제로 나갔을 수 있음)면 계산하지 않는다. 정산된 뒤의 마지막 변심 반품은 처음 배송비까지 돌려준다")
    void refundFailedReturnBlocksNextRefund() {
        Order order = order(domesticQuote(quoteLine(1, 20_000, 1), quoteLine(2, 20_000, 1)));
        Claim first = claim(order, 1, Claim.Status.REFUND_FAILED, item(order, 0), 1, 20_000, 0);
        Claim second = claim(order, 2, Claim.Status.REQUESTED, item(order, 1), 1, 0, 0);

        assertThatThrownBy(() -> Settled.of(order, List.of(first, second), second)).as("겹쳐 승인하면 20,000 — 처음 배송비 3,000 영영 미환불")
            .isInstanceOf(IllegalStateException.class);

        Claim firstSettled = claim(order, 1, Claim.Status.COMPLETED, item(order, 0), 1, 20_000, 0);
        Settled settled = Settled.of(order, List.of(firstSettled, second), second);
        assertThat(ReturnRefundPolicy.refund(order, settled, second, Fault.CUSTOMER).policyAmount()).as("남은 결제액 23,000 전부").isEqualTo(23_000);
        assertThat(order.quote().totalAmount().amount()).isEqualTo(20_000 + 23_000);
    }

    @Test
    @DisplayName("대상은 접수(REQUESTED) 반품만 — 진행 · 정산한 반품은 다시 계산하지 않는다 (결제 LOW 6)")
    void targetMustBeRequestedAndNotSettled() {
        Order order = order(domesticQuote(quoteLine(1, 10_000, 2), quoteLine(2, 5_000, 1)));
        long a = item(order, 0);
        Claim target = claim(order, 2, Claim.Status.REQUESTED, a, 1, 0, 0);

        for (Claim.Status status : List.of(Claim.Status.APPROVED, Claim.Status.REFUNDED, Claim.Status.COMPLETED, Claim.Status.REJECTED)) {
            Claim notRequested = claim(order, 2, status, a, 1, 1_000, 0);
            assertThatThrownBy(() -> Settled.of(order, List.of(), notRequested)).as(status.name()).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> Settled.of(order, List.of(notRequested), target)).as("목록의 같은 클레임이 이미 " + status)
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ReturnRefundPolicy.refund(order, Settled.none(order), notRequested, Fault.CUSTOMER)).isInstanceOf(IllegalStateException.class);
        }
        Settled containingTarget = new Settled(Map.of(a, 1), 5_000, CurrencyCode.KRW, Set.of(target.id()));
        assertThatThrownBy(() -> ReturnRefundPolicy.refund(order, containingTarget, target, Fault.CUSTOMER)).as("정산에 이미 들어간 클레임")
            .isInstanceOf(IllegalStateException.class);
        assertThat(Settled.of(order, List.of(target), target).amount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = {"REJECTED", "APPROVED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("거절이 아닌 취소 클레임이 있으면 정산 · 정리 판정을 하지 않는다 — 부분 취소(#684) 전까지 fail-closed (결제 MEDIUM 2)")
    void liveCancellationFailsClosed(Claim.Status status) {
        Order order = order(domesticQuote(quoteLine(1, 10_000, 2), quoteLine(2, 5_000, 1)));
        long a = item(order, 0);
        Claim target = claim(order, 2, Claim.Status.REQUESTED, a, 1, 0, 0);
        Claim cancellation = new Claim(31, order.id(), UUID.randomUUID(), Claim.Type.CANCELLATION, status, krw(28_000), 77L, 0,
            Claim.Type.CANCELLATION.isAwaitingAttempt(status) ? NOW : null, status.isFailed() ? "PAYMENT_016" : null,
            List.of(new ClaimItem(311, a, 2, ClaimItem.Reason.OTHER)), NOW, 0L);

        assertThatThrownBy(() -> Settled.of(order, List.of(cancellation, target), target)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ReturnRefundPolicy.outcome(order, List.of(cancellation))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("판매자 귀책이 만든 재부과는 판매자가 진다 (사용자 결정 11-13) — 불량 A 40,000 환불 뒤 변심 B 는 7,000 이 아니라 10,000, 합은 결제 총액 60,000")
    void sellerAbsorbsRechargeItCaused() {
        Order order = order(domesticQuote(quoteLine(1, 40_000, 1), quoteLine(2, 10_000, 1), quoteLine(3, 10_000, 1)));
        long a = item(order, 0);
        long b = item(order, 1);
        long c = item(order, 2);

        Refund sellerA = refund(order, Settled.none(order), Map.of(a, 1), Fault.SELLER);
        Refund customerB = refund(order, new Settled(Map.of(a, 1), sellerA.policyAmount(), CurrencyCode.KRW), Map.of(b, 1), Fault.CUSTOMER);
        Refund customerC = refund(order, new Settled(Map.of(a, 1, b, 1), sellerA.policyAmount() + customerB.policyAmount(), CurrencyCode.KRW),
            Map.of(c, 1), Fault.CUSTOMER);

        assertThat(sellerA.policyAmount()).isEqualTo(40_000);
        assertThat(customerB.policyAmount()).as("due(B,C) 23,000 − due(C) 13,000 — 남은 결제액 20,000 − 13,000 = 7,000 이 아니다").isEqualTo(10_000);
        assertThat(customerC.policyAmount()).as("남은 결제액 10,000 전부 — 재부과 3,000 은 고객이 내지 않았다").isEqualTo(10_000);
        assertThat(sellerA.policyAmount() + customerB.policyAmount() + customerC.policyAmount()).isEqualTo(60_000);
        assertThat(sumOf(order, List.of(Map.of(a, 1), Map.of(b, 1), Map.of(c, 1)), List.of(Fault.SELLER, Fault.CUSTOMER, Fault.SELLER))).isEqualTo(60_000);
    }

    @Test
    @DisplayName("귀책 · 순서를 모두 바꿔 한 개씩 다 반품해도 매 단계 정책 금액은 0 이상 · 남은 결제액 이하이고 합은 결제 총액이다 — 국내 무료 · 유료 · 해외 무료배송")
    void anyFaultAndOrderNeverExceedsTotal() {
        ShippingFeeQuote international = new ShippingFeeQuote(krw(0), krw(5_000), true, krw(0), null, krw(100_000), 4L, ShippingScope.INTERNATIONAL, "북미",
            krw(20_000), 1_000);
        List<Order> orders = List.of(
            order(domesticQuote(quoteLine(1, 40_000, 1), quoteLine(2, 10_000, 1), quoteLine(3, 10_000, 1))),
            order(domesticQuote(quoteLine(1, 10_000, 2), quoteLine(2, 5_000, 1))),
            order(domesticQuote(quoteLine(1, 30_000, 1), quoteLine(2, 25_000, 1))),
            order(quote(CurrencyCode.KRW, "US", international, quoteLine(1, 60_000, 2), quoteLine(2, 30_000, 1))));
        for (Order order : orders) {
            List<Long> units = new ArrayList<>();
            for (int line = 0; line < order.itemIds().size(); line++) {
                for (int count = 0; count < order.quote().lines().get(line).quantity(); count++) {
                    units.add(item(order, line));
                }
            }
            long total = order.quote().totalAmount().amount();
            for (List<Long> sequence : permutations(units)) {
                for (int mask = 0; mask < (1 << sequence.size()); mask++) {
                    Map<Long, Integer> settledQuantities = new HashMap<>();
                    long settledAmount = 0;
                    for (int step = 0; step < sequence.size(); step++) {
                        Fault fault = (mask & (1 << step)) == 0 ? Fault.CUSTOMER : Fault.SELLER;
                        long amount = refund(order, new Settled(settledQuantities, settledAmount, CurrencyCode.KRW), Map.of(sequence.get(step), 1), fault)
                            .policyAmount();
                        assertThat(amount).as("%s mask %d step %d", sequence, mask, step).isBetween(0L, total - settledAmount);
                        settledAmount += amount;
                        settledQuantities.merge(sequence.get(step), 1, Integer::sum);
                    }
                    assertThat(settledAmount).as("%s mask %d", sequence, mask).isEqualTo(total);
                }
            }
        }
    }

    @Test
    @DisplayName("반품 정리 판정 — 정산된 반품이 전 수량이면 반품 완료, 아니고 열린 반품(접수 · 승인 · 환불 실패)이 있으면 유지, 그 밖이면 배송 완료")
    void outcome() {
        Order order = order(domesticQuote(quoteLine(1, 10_000, 2), quoteLine(2, 5_000, 1)));
        long first = item(order, 0);
        long second = item(order, 1);

        assertThat(ReturnRefundPolicy.outcome(order, List.of())).isEqualTo(ReturnOutcome.DELIVERED);
        assertThat(ReturnRefundPolicy.outcome(order, List.of(claim(order, 1, Claim.Status.REQUESTED, first, 1, 0, 0)))).isEqualTo(ReturnOutcome.STILL_REQUESTED);
        assertThat(ReturnRefundPolicy.outcome(order, List.of(claim(order, 1, Claim.Status.APPROVED, first, 2, 1_000, 0)))).isEqualTo(ReturnOutcome.STILL_REQUESTED);
        assertThat(ReturnRefundPolicy.outcome(order, List.of(claim(order, 1, Claim.Status.REJECTED, first, 2, 0, 0)))).as("전부 거절")
            .isEqualTo(ReturnOutcome.DELIVERED);
        assertThat(ReturnRefundPolicy.outcome(order, List.of(claim(order, 1, Claim.Status.COMPLETED, first, 2, 1_000, 0)))).as("일부만 환불")
            .isEqualTo(ReturnOutcome.DELIVERED);
        assertThat(ReturnRefundPolicy.outcome(order, List.of(claim(order, 1, Claim.Status.COMPLETED, first, 2, 1_000, 0),
            claim(order, 2, Claim.Status.REFUND_FAILED, second, 1, 1_000, 0)))).as("환불 실패는 열린 반품").isEqualTo(ReturnOutcome.STILL_REQUESTED);
        assertThat(ReturnRefundPolicy.outcome(order, List.of(claim(order, 1, Claim.Status.COMPLETED, first, 1, 1_000, 0),
            claim(order, 2, Claim.Status.RESTORATION_FAILED, first, 1, 1_000, 0), claim(order, 3, Claim.Status.REFUNDED, second, 1, 1_000, 0),
            claim(order, 4, Claim.Status.REJECTED, second, 1, 0, 0)))).as("나눠 환불한 전 수량").isEqualTo(ReturnOutcome.RETURNED);
        Claim rejectedCancellation = new Claim(5, order.id(), UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.REJECTED, krw(25_000), 77L, 0, null,
            null, List.of(new ClaimItem(51, first, 2, ClaimItem.Reason.OTHER), new ClaimItem(52, second, 1, ClaimItem.Reason.OTHER)), NOW, 0L);
        assertThat(ReturnRefundPolicy.outcome(order, List.of(rejectedCancellation))).as("거절된 취소는 보지 않는다").isEqualTo(ReturnOutcome.DELIVERED);
        Claim foreign = new Claim(6, order.id() + 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(61, first, 1, ClaimItem.Reason.DEFECTIVE)), NOW, 0L);
        assertThatThrownBy(() -> ReturnRefundPolicy.outcome(order, List.of(foreign))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReturnRefundPolicy.outcome(order, List.of(claim(order, 1, Claim.Status.COMPLETED, second, 1, 1_000, 0),
            claim(order, 2, Claim.Status.COMPLETED, second, 1, 1_000, 0)))).as("환불 수량이 주문 수량을 넘는 데이터 이상").isInstanceOf(IllegalStateException.class);
        assertThat(ReturnOutcome.STILL_REQUESTED.getOrderStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(ReturnOutcome.RETURNED.getOrderStatus()).isEqualTo(OrderStatus.RETURNED);
        assertThat(ReturnOutcome.DELIVERED.getOrderStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("주문에 없는 항목 · 0 이하 수량 · 주문 수량을 넘는 누적 반품 · 결제 총액을 넘는 정산액은 계산하지 않는다")
    void invalidInputs() {
        Order order = order(domesticQuote(quoteLine(1, 30_000, 2)));
        long a = item(order, 0);
        Settled none = Settled.none(order);

        assertThatThrownBy(() -> refund(order, none, Map.of(999L, 1), Fault.CUSTOMER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refund(order, none, Map.of(a, 0), Fault.CUSTOMER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refund(order, none, Map.of(), Fault.CUSTOMER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refund(order, none, Map.of(a, 3), Fault.SELLER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refund(order, new Settled(Map.of(a, 2), 60_000, CurrencyCode.KRW), Map.of(a, 1), Fault.CUSTOMER))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refund(order, new Settled(Map.of(), 60_001, CurrencyCode.KRW), Map.of(a, 1), Fault.CUSTOMER))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settled(Map.of(a, -1), 0, CurrencyCode.KRW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settled(Map.of(), -1, CurrencyCode.KRW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refund(order, none, Map.of(a, 1), null)).isInstanceOf(NullPointerException.class);
    }

    /** 단계마다 앞 단계를 정산한 것으로 보고 환불 합을 낸다 — 차감액 0. */
    private static long sumOf(Order order, List<Map<Long, Integer>> steps, List<Fault> faults) {
        Map<Long, Integer> settledQuantities = new HashMap<>();
        long settledAmount = 0;
        for (int index = 0; index < steps.size(); index++) {
            Refund refund = refund(order, new Settled(settledQuantities, settledAmount, order.quote().currency()), steps.get(index), faults.get(index));
            settledAmount += refund.policyAmount();
            steps.get(index).forEach((id, quantity) -> settledQuantities.merge(id, quantity, Integer::sum));
        }
        return settledAmount;
    }

    private static Claim returnClaim(Order order, List<ClaimItem> items) {
        return new Claim(9, order.id(), UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null, items, NOW, 0L);
    }

    /** 반품 클레임 한 줄 — 승인 이후 상태면 환불 · 결제 · 승인 기록 · 다음 시각 · 실패 코드를 상태에 맞게 채운다. 접수 · 거절이면 비운다. */
    private static Claim claim(Order order, long id, Claim.Status status, long orderItemId, int quantity, long refund, long deduction) {
        List<ClaimItem> items = List.of(new ClaimItem(id * 10, orderItemId, quantity, ClaimItem.Reason.DEFECTIVE));
        if (status == Claim.Status.REQUESTED || status == Claim.Status.REJECTED) {
            return new Claim(id, order.id(), UUID.randomUUID(), Claim.Type.RETURN, status, null, items, NOW, 0L);
        }
        return new Claim(id, order.id(), UUID.randomUUID(), Claim.Type.RETURN, status, krw(refund), 77L, 0, Claim.Type.RETURN.isAwaitingAttempt(status) ? NOW : null,
            status.isFailed() ? "PAYMENT_016" : null, null, new Claim.ReturnApproval(true, krw(deduction), 9, NOW), items, NOW, 0L);
    }

    private static List<List<Long>> permutations(List<Long> units) {
        if (units.isEmpty()) {
            return List.of(List.of());
        }
        List<List<Long>> result = new ArrayList<>();
        for (int index = 0; index < units.size(); index++) {
            List<Long> rest = new ArrayList<>(units);
            Long head = rest.remove(index);
            for (List<Long> tail : permutations(rest)) {
                List<Long> sequence = new ArrayList<>();
                sequence.add(head);
                sequence.addAll(tail);
                result.add(sequence);
            }
        }
        return result;
    }

    private static OrderQuote quote(CurrencyCode currency, String country, ShippingFeeQuote fee, OrderQuoteLine... lines) {
        Money items = List.of(lines).stream().map(OrderQuoteLine::lineAmount).reduce(Money.zero(currency), Money::plus);
        return new OrderQuote(currency, List.of(lines), items, fee, items.plus(fee.shippingFee()), country);
    }

    private static Money usd(long cents) {
        return Money.of(cents, CurrencyCode.USD);
    }
}
