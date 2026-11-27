package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo.Blocker;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo.FaultRefund;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo.ReturnDecision;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy.Fault;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 반품 결정 미리보기 (#17 B2c) — 승인 가능 판정 순서(결정 대상 → 진행 중 반품 → 결제 대조 · 산식)와 귀책별 정책 금액. 금액 · 아이디는 모두 가짜다.
 *
 * <p>주문은 무료배송(30,000 + 25,000 = 55,000, 기준 50,000)이다. 30,000 항목을 반품하면 남는 25,000 이 기준 미만이라 기본 배송비 3,000 을 다시 받는다 —
 * 고객 귀책 {@code 55,000 − (25,000 + 3,000) = 27,000}, 판매자 귀책은 반품 항목 금액 30,000 이다.
 */
class ClaimReturnDecisionProcessorTest {
    private static final Instant NOW = Instant.parse("2026-11-27T00:00:00Z");
    private static final long ORDER_ID = 1L;

    private final OrderRepositoryPort orders = mock(OrderRepositoryPort.class);
    private final ClaimRepositoryPort claims = mock(ClaimRepositoryPort.class);
    private final OrderPaymentRepositoryPort payments = mock(OrderPaymentRepositoryPort.class);
    private final ClaimReturnDecisionProcessor processor = new ClaimReturnDecisionProcessor(orders, claims, payments);

    private static Order order(OrderStatus status) {
        return order(status, domesticQuote(quoteLine(9001, 30_000, 1), quoteLine(9002, 25_000, 1)));
    }

    private static Order order(OrderStatus status, OrderQuote quote) {
        AtomicLong ids = new AtomicLong(100);
        List<Long> itemIds = quote.lines().stream().map(line -> ids.incrementAndGet()).toList();
        return new Order(ORDER_ID, 7L, status, quote, itemIds, address("KR"), 99L, Instant.EPOCH, NOW, NOW, Instant.EPOCH, 3L);
    }

    private static Claim returnClaim(long id, Order order, ClaimItem.Reason reason) {
        return new Claim(id, order.id(), UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(id + 1, order.itemIds().getFirst(), 1, reason)), NOW, 0L);
    }

    private static OrderPayment payment(Money total) {
        return new OrderPayment(5L, ORDER_ID, 77L, 7L, total, NOW, OrderPayment.Result.PAID, 99L, UUID.randomUUID(), null, null, null);
    }

    private void stored(Order order, List<Claim> orderClaims, OrderPayment payment) {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(claims.findAllByOrderId(ORDER_ID)).thenReturn(orderClaims);
        when(payments.findPaidByOrderId(ORDER_ID)).thenReturn(Optional.ofNullable(payment));
    }

    @Test
    @DisplayName("승인할 수 있으면 기본 귀책과 귀책별 정책 금액(차감 전)을 낸다 — 고객 귀책은 재부과를 빼고, 판매자 귀책은 반품 항목 금액이다")
    void approvablePreviewHasRefundByFault() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        stored(order, List.of(target), payment(order.quote().totalAmount()));

        ReturnDecision preview = processor.preview(target);

        assertThat(preview.approvable()).isTrue();
        assertThat(preview.rejectable()).isTrue();
        assertThat(preview.blocker()).isNull();
        assertThat(preview.defaultFault()).isEqualTo(Fault.CUSTOMER);
        assertThat(preview.refundByFault()).containsExactly(new FaultRefund(Fault.CUSTOMER, 27_000, CurrencyCode.KRW),
            new FaultRefund(Fault.SELLER, 30_000, CurrencyCode.KRW));
    }

    @Test
    @DisplayName("고객 귀책 정책 금액은 0 이하일 수 있고 그대로 낸다 — 무료배송 49,000 + 2,000 에서 2,000 을 변심 반품하면 재부과 3,000 이 더 크다")
    void negativeCustomerPolicyAmountIsReportedAsIs() {
        Order order = order(OrderStatus.RETURN_REQUESTED, domesticQuote(quoteLine(9001, 49_000, 1), quoteLine(9002, 2_000, 1)));
        Claim target = new Claim(200, ORDER_ID, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(201, order.itemIds().getLast(), 1, ClaimItem.Reason.CHANGE_OF_MIND)), NOW, 0L);
        stored(order, List.of(target), payment(order.quote().totalAmount()));

        ReturnDecision preview = processor.preview(target);

        // 결제 51,000, 남는 49,000 은 기준 미만이라 52,000 — 고객 51,000 − 52,000 = −1,000, 판매자는 반품 항목 금액 2,000
        assertThat(preview.approvable()).isTrue();
        assertThat(preview.refundByFault()).containsExactly(new FaultRefund(Fault.CUSTOMER, -1_000, CurrencyCode.KRW),
            new FaultRefund(Fault.SELLER, 2_000, CurrencyCode.KRW));
    }

    @Test
    @DisplayName("상세가 클레임을 읽은 뒤 그 사이 승인 · 거절됐으면(목록의 같은 클레임이 결정 대기가 아님) NOT_DECIDABLE — 결제는 읽지 않는다")
    void targetDecidedMeanwhileIsNotDecidable() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        Claim rejectedMeanwhile = target.rejectReturn(new Claim.Decision("검수 불합격", 9, NOW, null));
        stored(order, List.of(rejectedMeanwhile), payment(order.quote().totalAmount()));

        ReturnDecision preview = processor.preview(target);

        assertThat(preview.blocker()).isEqualTo(Blocker.NOT_DECIDABLE);
        assertThat(preview.approvable()).isFalse();
        assertThat(preview.rejectable()).isFalse();
        verify(payments, never()).findPaidByOrderId(anyLong());
    }

    @Test
    @DisplayName("기본 귀책은 항목 사유로 정한다 — 불량 · 오배송만이면 판매자다")
    void defaultFaultFollowsItemReasons() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.DEFECTIVE);
        stored(order, List.of(target), payment(order.quote().totalAmount()));

        assertThat(processor.preview(target).defaultFault()).isEqualTo(Fault.SELLER);
    }

    @Test
    @DisplayName("이미 정산한 반품은 남은 결제액에서 빼고 계산한다 — 거절 반품은 금액에 영향이 없다")
    void settledReturnReducesRemainingPaid() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = new Claim(200, ORDER_ID, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(201, order.itemIds().getLast(), 1, ClaimItem.Reason.CHANGE_OF_MIND)), NOW, 0L);
        Claim settled = new Claim(300, ORDER_ID, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.COMPLETED, krw(30_000), 77L, 0, null, null, null,
            new Claim.ReturnApproval(Fault.SELLER, true, krw(0), 9, NOW), List.of(new ClaimItem(301, order.itemIds().getFirst(), 1, ClaimItem.Reason.DEFECTIVE)),
            NOW, 1L);
        Claim rejected = returnClaim(400, order, ClaimItem.Reason.CHANGE_OF_MIND).rejectReturn(new Claim.Decision("검수 불합격", 9, NOW, null));
        stored(order, List.of(settled, rejected, target), payment(order.quote().totalAmount()));

        ReturnDecision preview = processor.preview(target);

        // 남은 결제액 25,000 — 마지막 항목이라 귀책과 무관하게 전부다
        assertThat(preview.refundByFault()).containsExactly(new FaultRefund(Fault.CUSTOMER, 25_000, CurrencyCode.KRW),
            new FaultRefund(Fault.SELLER, 25_000, CurrencyCode.KRW));
    }

    @Test
    @DisplayName("같은 주문의 다른 반품 환불이 진행 중(APPROVED · REFUND_FAILED)이면 RETURN_IN_FLIGHT — 결제는 읽지 않는다")
    void inFlightReturnBlocks() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        Claim approved = new Claim(300, ORDER_ID, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.APPROVED, krw(10_000), 77L, 0, NOW, null, null,
            new Claim.ReturnApproval(Fault.CUSTOMER, true, krw(0), 9, NOW), List.of(new ClaimItem(301, order.itemIds().getLast(), 1, ClaimItem.Reason.CHANGE_OF_MIND)),
            NOW, 1L);
        stored(order, List.of(approved, target), payment(order.quote().totalAmount()));

        ReturnDecision preview = processor.preview(target);

        assertThat(preview.approvable()).isFalse();
        assertThat(preview.rejectable()).as("진행 중 반품은 승인만 막는다").isTrue();
        assertThat(preview.blocker()).isEqualTo(Blocker.RETURN_IN_FLIGHT);
        assertThat(preview.defaultFault()).isEqualTo(Fault.CUSTOMER);
        assertThat(preview.refundByFault()).isEmpty();
        verify(payments, never()).findPaidByOrderId(anyLong());
    }

    @Test
    @DisplayName("결제 기록이 없거나 결제 금액이 주문 총액과 다르면 NOT_CALCULABLE")
    void missingOrMismatchedPaymentIsNotCalculable() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        stored(order, List.of(target), null);

        assertThat(processor.preview(target).blocker()).isEqualTo(Blocker.NOT_CALCULABLE);

        when(payments.findPaidByOrderId(ORDER_ID)).thenReturn(Optional.of(payment(krw(54_999))));
        ReturnDecision mismatched = processor.preview(target);
        assertThat(mismatched.blocker()).isEqualTo(Blocker.NOT_CALCULABLE);
        assertThat(mismatched.refundByFault()).isEmpty();
    }

    @Test
    @DisplayName("산식이 거부하는 데이터 이상(거절이 아닌 취소 클레임이 같은 주문에 있음)은 NOT_CALCULABLE — 예외로 상세를 깨지 않는다")
    void policyFailureIsNotCalculable() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        Claim cancellation = new Claim(300, ORDER_ID, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.COMPLETED, krw(55_000), 77L, 0, null, null,
            List.of(new ClaimItem(301, order.itemIds().getFirst(), 1, ClaimItem.Reason.ORDER_MISTAKE)), NOW, 1L);
        stored(order, List.of(cancellation, target), payment(order.quote().totalAmount()));

        assertThat(processor.preview(target).blocker()).isEqualTo(Blocker.NOT_CALCULABLE);
    }

    @Test
    @DisplayName("주문이 반품 요청이 아니면 NOT_DECIDABLE — 클레임 목록 · 결제는 읽지 않는다. 주문이 없으면 NOT_CALCULABLE")
    void orderNotReturnRequestedIsNotDecidable() {
        Order order = order(OrderStatus.DELIVERED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));

        ReturnDecision preview = processor.preview(target);

        assertThat(preview.blocker()).isEqualTo(Blocker.NOT_DECIDABLE);
        assertThat(preview.defaultFault()).isEqualTo(Fault.CUSTOMER);
        verifyNoInteractions(claims, payments);

        when(orders.findById(ORDER_ID)).thenReturn(Optional.empty());
        assertThat(processor.preview(target).blocker()).isEqualTo(Blocker.NOT_CALCULABLE);
    }

    @Test
    @DisplayName("결정 대기 반품이 아니면(거절 반품 · 취소 클레임) 주문도 읽지 않고 NOT_DECIDABLE — 취소에는 기본 귀책이 없다")
    void nonAwaitingClaimIsNotDecidableWithoutReads() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim rejected = returnClaim(200, order, ClaimItem.Reason.DEFECTIVE).rejectReturn(new Claim.Decision("검수 불합격", 9, NOW, null));
        Claim cancellation = new Claim(300, ORDER_ID, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.COMPLETED, krw(55_000), 77L, 0, null, null,
            List.of(new ClaimItem(301, order.itemIds().getFirst(), 1, ClaimItem.Reason.ORDER_MISTAKE)), NOW, 1L);

        ReturnDecision rejectedPreview = processor.preview(rejected);
        ReturnDecision cancellationPreview = processor.preview(cancellation);

        assertThat(rejectedPreview.blocker()).isEqualTo(Blocker.NOT_DECIDABLE);
        assertThat(rejectedPreview.defaultFault()).isEqualTo(Fault.SELLER);
        assertThat(cancellationPreview.blocker()).isEqualTo(Blocker.NOT_DECIDABLE);
        assertThat(cancellationPreview.defaultFault()).isNull();
        verifyNoInteractions(orders, claims, payments);
    }
}
