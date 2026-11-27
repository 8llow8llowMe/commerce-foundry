package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.command.ClaimReturnApprovalCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
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
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy.Fault;
import com.commercefoundry.domainlayer.order.domain.model.StatusChangedBy;
import com.commercefoundry.global.properties.OrderCancellationProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * 반품 결정 (#17 B2c) — 미리보기의 판정 순서(결정 대상 → 진행 중 반품 → 결제 대조 · 산식)와 귀책별 정책 금액, 승인 · 거절(B2c-2)이 같은 판정을 오류 코드로 내는지.
 * 금액 · 아이디는 모두 가짜다.
 *
 * <p>주문은 무료배송(30,000 + 25,000 = 55,000, 기준 50,000)이다. 30,000 항목을 반품하면 남는 25,000 이 기준 미만이라 기본 배송비 3,000 을 다시 받는다 —
 * 고객 귀책 {@code 55,000 − (25,000 + 3,000) = 27,000}, 판매자 귀책은 반품 항목 금액 30,000 이다.
 */
class ClaimReturnDecisionProcessorTest {
    private static final Instant NOW = Instant.parse("2026-11-27T00:00:00Z");
    private static final long ORDER_ID = 1L;
    private static final long ADMIN = 9L;
    private static final long PAYMENT_ID = 77L;
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final String REASON = "검수 결과 사용 흔적이 있어 반품 불가";

    private final OrderRepositoryPort orders = mock(OrderRepositoryPort.class);
    private final ClaimRepositoryPort claims = mock(ClaimRepositoryPort.class);
    private final OrderPaymentRepositoryPort payments = mock(OrderPaymentRepositoryPort.class);

    private static OrderCancellationProperties properties(boolean retryEnabled) {
        return new OrderCancellationProperties(false, new OrderCancellationProperties.Retry(retryEnabled, Duration.ofMinutes(1), 50, 48,
            Duration.ofMinutes(1), Duration.ofHours(1), LEASE));
    }

    private ClaimReturnDecisionProcessor processor(boolean retryEnabled) {
        return new ClaimReturnDecisionProcessor(orders, claims, payments, new SnowflakeIdGenerator(1, 1), properties(retryEnabled),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private final ClaimReturnDecisionProcessor processor = processor(true);

    @BeforeEach
    void savesWhatItIsGiven() {
        when(claims.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

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
        return new OrderPayment(5L, ORDER_ID, PAYMENT_ID, 7L, total, NOW, OrderPayment.Result.PAID, 99L, UUID.randomUUID(), null, null, null);
    }

    private static Claim approvedOther(Order order) {
        return new Claim(300, ORDER_ID, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.APPROVED, krw(10_000), PAYMENT_ID, 0, NOW, null, null,
            new Claim.ReturnApproval(Fault.CUSTOMER, true, krw(0), ADMIN, NOW),
            List.of(new ClaimItem(301, order.itemIds().getLast(), 1, ClaimItem.Reason.CHANGE_OF_MIND)), NOW, 1L);
    }

    private static Claim liveCancellation(Order order) {
        return new Claim(300, ORDER_ID, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.COMPLETED, krw(55_000), PAYMENT_ID, 0, null, null,
            List.of(new ClaimItem(301, order.itemIds().getFirst(), 1, ClaimItem.Reason.ORDER_MISTAKE)), NOW, 1L);
    }

    private void stored(Order order, List<Claim> orderClaims, OrderPayment payment) {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(claims.findAllByOrderId(ORDER_ID)).thenReturn(orderClaims);
        when(payments.findPaidByOrderId(ORDER_ID)).thenReturn(Optional.ofNullable(payment));
        orderClaims.forEach(claim -> when(claims.findById(claim.id())).thenReturn(Optional.of(claim)));
    }

    private static ClaimReturnApprovalCommand approval(Claim target, Fault fault, long deduction, long expectedRefund) {
        return new ClaimReturnApprovalCommand(target.id(), target.version(), fault, true, krw(deduction), krw(expectedRefund));
    }

    private static void assertCode(Runnable call, OrderErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(OrderException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(code));
    }

    // ---- 미리보기 (B2c-1) ----

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
    @DisplayName("재시도 스케줄러가 꺼져 있으면 데이터가 승인 가능해도 REFUND_NOT_OPEN — 승인은 막고(승인 503 ORDER_040과 같다) 거절은 되며 금액은 표시용으로 싣는다")
    void closedRefundGateBlocksApprovalInPreview() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        stored(order, List.of(target), payment(order.quote().totalAmount()));

        ReturnDecision preview = processor(false).preview(target);

        assertThat(preview.blocker()).isEqualTo(Blocker.REFUND_NOT_OPEN);
        assertThat(preview.approvable()).isFalse();
        assertThat(preview.rejectable()).isTrue();
        assertThat(preview.refundByFault()).containsExactly(new FaultRefund(Fault.CUSTOMER, 27_000, CurrencyCode.KRW),
            new FaultRefund(Fault.SELLER, 30_000, CurrencyCode.KRW));

        // 데이터 이유가 있으면 그것을 먼저 보인다
        stored(order, List.of(approvedOther(order), target), payment(order.quote().totalAmount()));
        assertThat(processor(false).preview(target).blocker()).isEqualTo(Blocker.RETURN_IN_FLIGHT);
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
        stored(order, List.of(approvedOther(order), target), payment(order.quote().totalAmount()));

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
        stored(order, List.of(liveCancellation(order), target), payment(order.quote().totalAmount()));

        assertThat(processor.preview(target).blocker()).isEqualTo(Blocker.NOT_CALCULABLE);
    }

    @Test
    @DisplayName("주문이 반품 요청이 아니거나 없으면 NOT_DECIDABLE(거절도 막힘) — 클레임 목록 · 결제는 읽지 않는다. 주문이 없으면 거절도 주문을 정리할 수 없다(B2c-2)")
    void orderNotReturnRequestedOrMissingIsNotDecidable() {
        Order order = order(OrderStatus.DELIVERED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));

        ReturnDecision preview = processor.preview(target);

        assertThat(preview.blocker()).isEqualTo(Blocker.NOT_DECIDABLE);
        assertThat(preview.defaultFault()).isEqualTo(Fault.CUSTOMER);

        when(orders.findById(ORDER_ID)).thenReturn(Optional.empty());
        ReturnDecision missing = processor.preview(target);
        assertThat(missing.blocker()).isEqualTo(Blocker.NOT_DECIDABLE);
        assertThat(missing.rejectable()).isFalse();
        verifyNoInteractions(claims, payments);
    }

    @Test
    @DisplayName("결정 대기 반품이 아니면(거절 반품 · 취소 클레임) 주문도 읽지 않고 NOT_DECIDABLE — 취소에는 기본 귀책이 없다")
    void nonAwaitingClaimIsNotDecidableWithoutReads() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim rejected = returnClaim(200, order, ClaimItem.Reason.DEFECTIVE).rejectReturn(new Claim.Decision("검수 불합격", 9, NOW, null));

        ReturnDecision rejectedPreview = processor.preview(rejected);
        ReturnDecision cancellationPreview = processor.preview(liveCancellation(order));

        assertThat(rejectedPreview.blocker()).isEqualTo(Blocker.NOT_DECIDABLE);
        assertThat(rejectedPreview.defaultFault()).isEqualTo(Fault.SELLER);
        assertThat(cancellationPreview.blocker()).isEqualTo(Blocker.NOT_DECIDABLE);
        assertThat(cancellationPreview.defaultFault()).isNull();
        verifyNoInteractions(orders, claims, payments);
    }

    // ---- 승인 (B2c-2) ----

    @Test
    @DisplayName("승인 — 귀책이 없으면 기본 귀책, 환불액은 정책 금액 − 차감액이고 결제 아이디 · 승인 기록 · lease 를 싣는다. 주문은 잠가서 읽고 바꾸지 않는다")
    void approvesWithDefaultFaultAndDeduction() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        stored(order, List.of(target), payment(order.quote().totalAmount()));

        Claim approved = processor.approve(approval(target, null, 3_000, 24_000), ADMIN);

        assertThat(approved.status()).isEqualTo(Claim.Status.APPROVED);
        assertThat(approved.expectedRefund()).isEqualTo(krw(24_000));
        assertThat(approved.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(approved.nextAttemptAt()).isEqualTo(NOW.plus(LEASE));
        assertThat(approved.approval()).isEqualTo(new Claim.ReturnApproval(Fault.CUSTOMER, true, krw(3_000), ADMIN, NOW));
        verify(claims).update(approved);
        verify(orders).findByIdForUpdate(ORDER_ID);
        verify(orders, never()).findById(anyLong());
        verify(orders, never()).saveStatusChange(any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Fault.class)
    @DisplayName("같은 입력이면 미리보기의 귀책별 정책 금액 − 차감액이 승인 환불액과 같다 — 운영자가 본 금액 그대로 승인된다")
    void previewAndApprovalAgree(Fault fault) {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        stored(order, List.of(target), payment(order.quote().totalAmount()));
        long policy = processor.preview(target).refundByFault().stream().filter(refund -> refund.fault() == fault).findFirst().orElseThrow().policyAmount();

        Claim approved = processor.approve(approval(target, fault, 1_000, policy - 1_000), ADMIN);

        assertThat(approved.expectedRefund()).isEqualTo(krw(policy - 1_000));
        assertThat(approved.approval().fault()).isEqualTo(fault);
    }

    @Test
    @DisplayName("승인 — 재시도 스케줄러가 꺼져 있으면 아무것도 읽지 않고 503 ORDER_040")
    void approvalNeedsRetryScheduler() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);

        assertCode(() -> processor(false).approve(approval(target, null, 0, 27_000), ADMIN), OrderErrorCode.CLAIM_RETRY_UNAVAILABLE);
        verifyNoInteractions(orders, claims, payments);
    }

    @Test
    @DisplayName("승인 · 거절 — 없는 접수 404 ORDER_032, 버전 불일치 409 ORDER_037, 결정 대기가 아닌 반품은 주문을 잠그지 않고 409 ORDER_042")
    void loadsExpectedAwaitingClaim() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        when(claims.findById(200)).thenReturn(Optional.empty());
        assertCode(() -> processor.approve(approval(target, null, 0, 27_000), ADMIN), OrderErrorCode.NOT_FOUND_CLAIM);
        assertCode(() -> processor.reject(200, 0, REASON, ADMIN), OrderErrorCode.NOT_FOUND_CLAIM);

        when(claims.findById(200)).thenReturn(Optional.of(target));
        assertCode(() -> processor.approve(new ClaimReturnApprovalCommand(200, 1, null, true, krw(0), krw(27_000)), ADMIN),
            OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        assertCode(() -> processor.reject(200, 1, REASON, ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);

        Claim rejected = target.rejectReturn(new Claim.Decision("검수 불합격", ADMIN, NOW, null));
        when(claims.findById(200)).thenReturn(Optional.of(rejected));
        assertCode(() -> processor.approve(approval(rejected, null, 0, 27_000), ADMIN), OrderErrorCode.RETURN_NOT_DECIDABLE);
        assertCode(() -> processor.reject(200, 0, REASON, ADMIN), OrderErrorCode.RETURN_NOT_DECIDABLE);
        verifyNoInteractions(orders, payments);
    }

    @Test
    @DisplayName("승인 · 거절 — 같은 주문 잠금에서 지면(NOWAIT) 기다리지 않고 409 ORDER_037")
    void orderLockConflictIsConcurrentChange() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        stored(order, List.of(target), payment(order.quote().totalAmount()));
        when(orders.findByIdForUpdate(ORDER_ID)).thenThrow(new CannotAcquireLockException("nowait"));

        assertCode(() -> processor.approve(approval(target, null, 0, 27_000), ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        assertCode(() -> processor.reject(200, 0, REASON, ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        verify(claims, never()).update(any());
    }

    @Test
    @DisplayName("승인 · 거절 — 주문이 없거나 반품 요청이 아니면 409 ORDER_042 (미리보기 NOT_DECIDABLE 과 같은 판정)")
    void orderNotReturnRequestedIsNotDecidable() {
        Order order = order(OrderStatus.DELIVERED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        stored(order, List.of(target), payment(order.quote().totalAmount()));

        assertCode(() -> processor.approve(approval(target, null, 0, 27_000), ADMIN), OrderErrorCode.RETURN_NOT_DECIDABLE);
        assertCode(() -> processor.reject(200, 0, REASON, ADMIN), OrderErrorCode.RETURN_NOT_DECIDABLE);

        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());
        assertCode(() -> processor.approve(approval(target, null, 0, 27_000), ADMIN), OrderErrorCode.RETURN_NOT_DECIDABLE);
        assertCode(() -> processor.reject(200, 0, REASON, ADMIN), OrderErrorCode.RETURN_NOT_DECIDABLE);
        verify(claims, never()).update(any());
    }

    @Test
    @DisplayName("승인 — 같은 주문의 다른 반품 환불 진행 중 409 ORDER_043 (미리보기 RETURN_IN_FLIGHT)")
    void inFlightReturnBlocksApproval() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        stored(order, List.of(approvedOther(order), target), payment(order.quote().totalAmount()));

        assertCode(() -> processor.approve(approval(target, null, 0, 27_000), ADMIN), OrderErrorCode.RETURN_REFUND_IN_FLIGHT);
        verify(payments, never()).findPaidByOrderId(anyLong());
        verify(claims, never()).update(any());
    }

    @Test
    @DisplayName("승인 — 결제 기록 없음 · 결제 금액 ≠ 주문 총액 · 산식 거부는 409 ORDER_045 (미리보기 NOT_CALCULABLE) — payment-service 는 부르지 않는다")
    void notCalculableApproval() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        stored(order, List.of(target), null);
        assertCode(() -> processor.approve(approval(target, null, 0, 27_000), ADMIN), OrderErrorCode.RETURN_REFUND_NOT_CALCULABLE);

        when(payments.findPaidByOrderId(ORDER_ID)).thenReturn(Optional.of(payment(krw(54_999))));
        assertCode(() -> processor.approve(approval(target, null, 0, 27_000), ADMIN), OrderErrorCode.RETURN_REFUND_NOT_CALCULABLE);

        stored(order, List.of(liveCancellation(order), target), payment(order.quote().totalAmount()));
        assertCode(() -> processor.approve(approval(target, null, 0, 27_000), ADMIN), OrderErrorCode.RETURN_REFUND_NOT_CALCULABLE);
        verify(claims, never()).update(any());
    }

    @Test
    @DisplayName("승인 — 환불액(정책 금액 − 차감액)이 0 이하면 409 ORDER_044: 음수 정책 금액 · 정책 금액 이상의 차감액 · 넘칠 만큼 큰 차감액")
    void nonPayableRefundIsRefused() {
        Order order = order(OrderStatus.RETURN_REQUESTED, domesticQuote(quoteLine(9001, 49_000, 1), quoteLine(9002, 2_000, 1)));
        Claim target = new Claim(200, ORDER_ID, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(201, order.itemIds().getLast(), 1, ClaimItem.Reason.CHANGE_OF_MIND)), NOW, 0L);
        stored(order, List.of(target), payment(order.quote().totalAmount()));

        // 고객 귀책 −1,000 — 차감 없이도 0 이하
        assertCode(() -> processor.approve(approval(target, Fault.CUSTOMER, 0, 1), ADMIN), OrderErrorCode.RETURN_REFUND_NOT_PAYABLE);
        // 판매자 귀책 2,000 − 2,000 = 0
        assertCode(() -> processor.approve(approval(target, Fault.SELLER, 2_000, 1), ADMIN), OrderErrorCode.RETURN_REFUND_NOT_PAYABLE);
        assertCode(() -> processor.approve(approval(target, Fault.CUSTOMER, Long.MAX_VALUE, 1), ADMIN), OrderErrorCode.RETURN_REFUND_NOT_PAYABLE);
        verify(claims, never()).update(any());
    }

    @Test
    @DisplayName("승인 — 차감액 통화 · 화면 환불액 · 화면 통화가 서버 계산과 다르면 409 ORDER_046")
    void changedRefundIsRefused() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        stored(order, List.of(target), payment(order.quote().totalAmount()));

        assertCode(() -> processor.approve(new ClaimReturnApprovalCommand(200, 0, null, true, Money.of(0, CurrencyCode.USD), krw(27_000)), ADMIN),
            OrderErrorCode.RETURN_REFUND_CHANGED);
        assertCode(() -> processor.approve(approval(target, null, 0, 26_999), ADMIN), OrderErrorCode.RETURN_REFUND_CHANGED);
        assertCode(() -> processor.approve(new ClaimReturnApprovalCommand(200, 0, null, true, krw(0), Money.of(27_000, CurrencyCode.USD)), ADMIN),
            OrderErrorCode.RETURN_REFUND_CHANGED);
        // 귀책을 바꾸면 금액도 바뀐다 — 고객 귀책 금액으로 판매자 귀책을 승인하지 않는다
        assertCode(() -> processor.approve(approval(target, Fault.SELLER, 0, 27_000), ADMIN), OrderErrorCode.RETURN_REFUND_CHANGED);
        verify(claims, never()).update(any());
    }

    @Test
    @DisplayName("승인 — 저장이 낙관락에서 지면 409 ORDER_037")
    void approvalSaveConflict() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        stored(order, List.of(target), payment(order.quote().totalAmount()));
        when(claims.update(any())).thenThrow(new OptimisticLockingFailureException("stale"));

        assertCode(() -> processor.approve(approval(target, null, 0, 27_000), ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
    }

    // ---- 거절 (B2c-2) ----

    @Test
    @DisplayName("거절 — 열린 반품이 이것뿐이면 REJECTED(결정 · 직전 실패 코드 없음) + 주문 배송 완료 + ADMIN 이력(사유)이 한 번에 저장된다")
    void rejectionSettlesOrderBackToDelivered() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        stored(order, List.of(target), null);

        Claim rejected = processor.reject(200, 0, REASON, ADMIN);

        assertThat(rejected.status()).isEqualTo(Claim.Status.REJECTED);
        assertThat(rejected.decision()).isEqualTo(new Claim.Decision(REASON, ADMIN, NOW, null));
        verify(claims).update(rejected);
        ArgumentCaptor<Order> settled = ArgumentCaptor.forClass(Order.class);
        ArgumentCaptor<OrderStatusHistory> history = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(orders).saveStatusChange(settled.capture(), history.capture());
        assertThat(settled.getValue().status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(history.getValue().fromStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(history.getValue().toStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(history.getValue().changedByType()).isEqualTo(StatusChangedBy.ADMIN);
        assertThat(history.getValue().changedById()).isEqualTo(ADMIN);
        assertThat(history.getValue().reason()).isEqualTo(REASON);
        verifyNoInteractions(payments);
    }

    @Test
    @DisplayName("거절 — 다른 반품이 열려 있으면(환불 진행 중이어도) 거절은 되고 주문은 반품 요청에 머문다(이력 없음)")
    void rejectionKeepsOrderRequestedWhileOtherReturnOpen() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        stored(order, List.of(approvedOther(order), target), null);

        assertThat(processor.reject(200, 0, REASON, ADMIN).status()).isEqualTo(Claim.Status.REJECTED);

        verify(orders, never()).saveStatusChange(any(), any());
    }

    @Test
    @DisplayName("거절 — 정리 판정이 불가능하면(거절이 아닌 취소 클레임) 저장하지 않고 409 ORDER_042, 저장 경합은 409 ORDER_037")
    void rejectionRefusals() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim target = returnClaim(200, order, ClaimItem.Reason.CHANGE_OF_MIND);
        stored(order, List.of(liveCancellation(order), target), null);
        assertCode(() -> processor.reject(200, 0, REASON, ADMIN), OrderErrorCode.RETURN_NOT_DECIDABLE);
        verify(claims, never()).update(any());

        stored(order, List.of(target), null);
        when(claims.update(any())).thenThrow(new OptimisticLockingFailureException("stale"));
        assertCode(() -> processor.reject(200, 0, REASON, ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        verify(orders, never()).saveStatusChange(any(), any());
    }
}
