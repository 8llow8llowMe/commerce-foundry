package com.commercefoundry;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentCancelPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelVoidQueryResult;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationPipelineProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 어드민 수동 환불 확정 · 복원 생략 종결 (#17 A3) · 반품 승인 · 거절 (#17 B2c-2) · 결제 후 취소 거절 (#17 A4) — 실제 JWT · 권한 · Facade · 단계별 트랜잭션 · H2 를
 * 관통하고 payment(환불 · 무환불 판정) · product 호출만 스텁한다.
 * 스텁은 실제 PG 환불의 증거가 아니다. 테스트 값은 모두 가짜다.
 *
 * <p>애노테이션 · 빈 대체(필드 이름까지)는 {@code ClaimAdminRetryWebIntegrationTest} · {@code OrderCancellationWebIntegrationTest} 와 같아 Spring 컨텍스트 캐시를
 * 공유한다 — 컨텍스트가 늘면 CI 에서 메모리가 모자란다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
// 수동 환불 확정은 이어지는 복원을 스케줄러가 따라갈 수 있어야 열린다. 스케줄러가 시험 중에 돌지 않게 주기를 길게 둔다
@TestPropertySource(properties = {"order.cancellation.retry.enabled=true", "order.cancellation.retry.interval=1h"})
class ClaimAdminDecisionWebIntegrationTest {

    private static final String REASON = "결제 관리 화면에서 취소 완료 확인";

    @Autowired MockMvcTester mvc;
    @Autowired OrderRepositoryPort orders;
    @Autowired OrderPaymentRepositoryPort payments;
    @Autowired SnowflakeIdGenerator ids;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean PaymentCancelPort paymentCancelPort;
    @MockitoBean StockRestorationPort stockRestorationPort;
    /** 쓰지 않지만 다른 취소 웹 통합 시험과 빈 구성을 같게 둬 컨텍스트를 공유한다. */
    @MockitoSpyBean OrderCancellationPipelineProcessor pipeline;
    /** 쓰지 않지만 다른 취소 웹 통합 시험과 빈 구성을 같게 둬 컨텍스트를 공유한다. */
    @MockitoSpyBean ClaimRepositoryPort claimRepository;

    @BeforeEach
    void succeedByDefault() {
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.succeeded());
    }

    private Order paidOrder() {
        Order order = Order.place(ids.generateId(), TestTokens.MEMBER_ID, domesticQuote(quoteLine(101, 10_000, 2)), ids::generateId, address("KR"),
            ids.generateId(), Instant.now().plusSeconds(600));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            orders.insert(order, OrderStatusHistory.created(ids.generateId(), order));
            jdbc.update("update orders.orders set status = 'PAID', version = version + 1 where id = ?", order.id());
            Order paid = new Order(order.id(), order.memberId(), OrderStatus.PAID, order.quote(), order.itemIds(), order.shippingAddress(),
                order.stockReservationId(), order.paymentDueAt(), null, null, null, 1L);
            payments.insert(OrderPayment.paid(ids.generateId(), paid, ids.generateId(), order.quote().totalAmount(), Instant.now(), UUID.randomUUID()));
        });
        return order;
    }

    private static String bearer(long member, MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(member, role).tokenValue();
    }

    private void customerCancel(long orderId) {
        assertThat(mvc.post().uri("/api/v1/orders/{orderId}/cancellation", orderId).header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.MEMBER_ID,
            MemberRole.CUSTOMER)).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ORDER_MISTAKE\"}").exchange()).hasStatus(200);
    }

    private Map<String, Object> claim(long orderId) {
        return jdbc.queryForMap("select id, status, failure_code, decision_reason, decided_by_member_id, decision_failure_code, version from orders.claim "
            + "where order_id = ? and type = 'CANCELLATION'", orderId);
    }

    private long claimId(long orderId) {
        return ((Number) claim(orderId).get("id")).longValue();
    }

    private long version(long orderId) {
        return ((Number) claim(orderId).get("version")).longValue();
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders.orders where id = ?", String.class, orderId);
    }

    private MvcTestResult detail(long claimId) {
        return mvc.get().uri("/api/v1/admin/claims/{claimId}", claimId).header(HttpHeaders.AUTHORIZATION, bearer(1, MemberRole.ADMIN)).exchange();
    }

    private MvcTestResult decide(String action, long claimId, long expectedVersion, MemberRole role) {
        return mvc.post().uri("/api/v1/admin/claims/{claimId}/" + action, claimId).header(HttpHeaders.AUTHORIZATION, bearer(1, role))
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":" + expectedVersion + ",\"reason\":\"" + REASON + "\"}").exchange();
    }

    /** 결제사가 거절해 환불 실패로 멈춘 취소 — 주문은 취소 요청 그대로다. */
    private long rejectedRefundClaim(Order order) {
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.rejected("PAYMENT_019"));
        customerCancel(order.id());
        assertThat(claim(order.id())).containsEntry("status", "REFUND_FAILED").containsEntry("failure_code", "PAYMENT_019");
        assertThat(orderStatus(order.id())).isEqualTo("CANCEL_REQUESTED");
        return claimId(order.id());
    }

    @Test
    @DisplayName("거절된 환불을 대표가 확정하면 PG 를 다시 부르지 않고 주문 취소 완료(ADMIN 이력 · 사유) → 재고 복원까지 이어 상세에 결정이 보인다")
    void confirmedRefundCompletesCancellation() {
        Order order = paidOrder();
        long claimId = rejectedRefundClaim(order);

        MvcTestResult before = detail(claimId);
        assertThat(before).bodyJson().extractingPath("$.dataBody.recovery.situation.code").isEqualTo("REFUND_REJECTED");
        assertThat(before).bodyJson().extractingPath("$.dataBody.recovery.retryable").isEqualTo(false);
        assertThat(before).bodyJson().extractingPath("$.dataBody.recovery.refundConfirmable").isEqualTo(true);
        assertThat(before).bodyJson().extractingPath("$.dataBody.recovery.completable").isEqualTo(false);
        assertThat(before).bodyJson().extractingPath("$.dataBody.recovery.lastDecision").isNull();
        long seen = version(order.id());

        MvcTestResult confirmed = decide("refund-confirmation", claimId, seen, MemberRole.OWNER);

        assertThat(confirmed).hasStatus(200);
        assertThat(confirmed).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("COMPLETED");
        assertThat(confirmed).bodyJson().extractingPath("$.dataBody.recovery.situation.code").isEqualTo("NO_ACTION");
        assertThat(confirmed).bodyJson().extractingPath("$.dataBody.recovery.refundConfirmable").isEqualTo(false);
        assertThat(confirmed).bodyJson().extractingPath("$.dataBody.recovery.lastDecision.reason").isEqualTo(REASON);
        assertThat(confirmed).bodyJson().extractingPath("$.dataBody.recovery.lastDecision.decidedAt").isNotNull();
        assertThat(confirmed.getResponse().getContentAsByteArray()).asString(java.nio.charset.StandardCharsets.UTF_8)
            .doesNotContain("decidedBy").doesNotContain("PAYMENT_019");
        assertThat(orderStatus(order.id())).isEqualTo("CANCELED");
        assertThat(jdbc.queryForMap("select from_status, changed_by_type, changed_by_id, reason from orders.order_status_history "
            + "where order_id = ? and to_status = 'CANCELED'", order.id()))
            .containsEntry("from_status", "CANCEL_REQUESTED").containsEntry("changed_by_type", "ADMIN").containsEntry("reason", REASON)
            .hasEntrySatisfying("changed_by_id", id -> assertThat(((Number) id).longValue()).isEqualTo(1L));
        assertThat(claim(order.id())).containsEntry("decision_reason", REASON).containsEntry("decision_failure_code", "PAYMENT_019")
            .containsEntry("failure_code", null);
        verify(paymentCancelPort, times(1)).cancel(anyLong(), anyLong(), any());
        verify(stockRestorationPort, times(1)).restoreCancellation(eq(claimId), anyLong(), any());

        // 같은 화면으로 다시 보낸 확정(더블클릭 · 재전송)은 버전이 올라 409 ORDER_037 이고 아무것도 다시 하지 않는다
        MvcTestResult replay = decide("refund-confirmation", claimId, seen, MemberRole.OWNER);
        assertThat(replay).hasStatus(409);
        assertThat(replay).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_037");
        verify(stockRestorationPort, times(1)).restoreCancellation(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("확정 뒤 재고 복원이 거절되면 RESTORATION_FAILED 로 멈추고, 재고를 맞춘 대표가 복원 없이 종결하면 COMPLETED 다 — 복원은 다시 부르지 않는다")
    void rejectedRestorationCanBeCompletedWithoutRestoring() {
        Order order = paidOrder();
        long claimId = rejectedRefundClaim(order);
        doThrow(new StockReservationException(StockReservationException.Failure.QUANTITY_EXCEEDED))
            .when(stockRestorationPort).restoreCancellation(anyLong(), anyLong(), any());

        MvcTestResult confirmed = decide("refund-confirmation", claimId, version(order.id()), MemberRole.ADMIN);
        assertThat(confirmed).hasStatus(200);
        assertThat(confirmed).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("RESTORATION_FAILED");
        assertThat(confirmed).bodyJson().extractingPath("$.dataBody.recovery.situation.code").isEqualTo("RESTORATION_REJECTED");
        assertThat(confirmed).bodyJson().extractingPath("$.dataBody.recovery.completable").isEqualTo(true);
        assertThat(orderStatus(order.id())).isEqualTo("CANCELED");

        MvcTestResult completed = decide("completion", claimId, version(order.id()), MemberRole.OWNER);

        assertThat(completed).hasStatus(200);
        assertThat(completed).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("COMPLETED");
        assertThat(completed).bodyJson().extractingPath("$.dataBody.recovery.completable").isEqualTo(false);
        assertThat(completed).bodyJson().extractingPath("$.dataBody.recovery.lastDecision.reason").isEqualTo(REASON);
        assertThat(claim(order.id())).containsEntry("status", "COMPLETED").containsEntry("decision_failure_code", "PRODUCT_028");
        assertThat(orderStatus(order.id())).isEqualTo("CANCELED");
        verify(stockRestorationPort, times(1)).restoreCancellation(anyLong(), anyLong(), any());
        verify(paymentCancelPort, times(1)).cancel(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("복원 결과 미확인(RESTORATION_UNCONFIRMED)은 종결 후보가 아니고 409 ORDER_041 이다 — 실제로 복원됐을 수 있어 다시 시도로 확인한다")
    void unconfirmedRestorationCannotBeCompleted() {
        Order order = paidOrder();
        customerCancel(order.id());
        jdbc.update("update orders.claim set status = 'RESTORATION_FAILED', failure_code = 'RESTORATION_UNCONFIRMED', next_attempt_at = null, "
            + "version = version + 1 where order_id = ? and type = 'CANCELLATION'", order.id());
        long claimId = claimId(order.id());
        long before = version(order.id());

        assertThat(detail(claimId)).bodyJson().extractingPath("$.dataBody.recovery.completable").isEqualTo(false);
        MvcTestResult result = decide("completion", claimId, before, MemberRole.OWNER);

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_041");
        assertThat(version(order.id())).isEqualTo(before);
    }

    private long historyCount(long orderId) {
        return jdbc.queryForObject("select count(*) from orders.order_status_history where order_id = ?", Long.class, orderId);
    }

    private long orderVersion(long orderId) {
        return jdbc.queryForObject("select version from orders.orders where id = ?", Long.class, orderId);
    }

    @Test
    @DisplayName("확정의 주문 저장이 낙관락에서 지면 409 ORDER_037 이고 같은 트랜잭션의 클레임 확정 · 결정도 함께 롤백된다 — 클레임 · 주문 · 이력이 그대로다")
    void orderSaveConflictRollsBackClaimDecision() {
        Order order = paidOrder();
        long claimId = rejectedRefundClaim(order);
        Map<String, Object> claimBefore = claim(order.id());
        long orderVersionBefore = orderVersion(order.id());
        long historyBefore = historyCount(order.id());
        // 클레임 저장 직후 · 주문 저장 직전에 같은 트랜잭션 연결로 주문 버전을 올린다 — 주문 저장의 버전 비교가 실제로 져
        // ObjectOptimisticLockingFailureException 이 난다(스텁이 던지는 가짜 예외가 아니다). 주입된 빈은 트랜잭션 프록시라 그 안의 spy 에 건다
        doAnswer(invocation -> {
            Object saved = invocation.callRealMethod();
            jdbc.update("update orders.orders set version = version + 1 where id = ?", order.id());
            return saved;
        }).when(org.springframework.test.util.AopTestUtils.<ClaimRepositoryPort>getUltimateTargetObject(claimRepository)).update(any());

        MvcTestResult result = decide("refund-confirmation", claimId, version(order.id()), MemberRole.OWNER);

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_037");
        assertThat(claim(order.id())).isEqualTo(claimBefore).containsEntry("status", "REFUND_FAILED").containsEntry("failure_code", "PAYMENT_019")
            .containsEntry("decision_reason", null).containsEntry("decided_by_member_id", null).containsEntry("decision_failure_code", null);
        assertThat(orderStatus(order.id())).isEqualTo("CANCEL_REQUESTED");
        assertThat(orderVersion(order.id())).as("같은 트랜잭션에서 올린 버전도 롤백된다").isEqualTo(orderVersionBefore);
        assertThat(historyCount(order.id())).isEqualTo(historyBefore);
        verify(stockRestorationPort, never()).restoreCancellation(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("주문 상태가 맞지 않아 닫힌 접수(ORDER_STATUS_UNEXPECTED)는 종결 후보가 아니다 — completable=false, 종결 409 ORDER_041")
    void unexpectedOrderStatusIsNotCompletable() {
        Order order = paidOrder();
        customerCancel(order.id());
        jdbc.update("update orders.claim set status = 'RESTORATION_FAILED', failure_code = 'ORDER_STATUS_UNEXPECTED', next_attempt_at = null, "
            + "version = version + 1 where order_id = ? and type = 'CANCELLATION'", order.id());
        long claimId = claimId(order.id());

        MvcTestResult detail = detail(claimId);
        assertThat(detail).bodyJson().extractingPath("$.dataBody.recovery.situation.code").isEqualTo("ORDER_STATUS_MISMATCH");
        assertThat(detail).bodyJson().extractingPath("$.dataBody.recovery.completable").isEqualTo(false);
        assertThat(detail).bodyJson().extractingPath("$.dataBody.recovery.retryable").isEqualTo(false);
        MvcTestResult result = decide("completion", claimId, version(order.id()), MemberRole.OWNER);

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_041");
    }

    @Test
    @DisplayName("다시 시도는 되는 MANAGER 도 실제 보안 체인에서 확정 · 종결은 403 이고 클레임 · 주문이 바뀌지 않는다")
    void managerMayNotDecide() {
        Order order = paidOrder();
        long claimId = rejectedRefundClaim(order);
        long before = version(order.id());

        assertThat(decide("refund-confirmation", claimId, before, MemberRole.MANAGER)).hasStatus(403);
        assertThat(decide("completion", claimId, before, MemberRole.MANAGER)).hasStatus(403);

        assertThat(version(order.id())).isEqualTo(before);
        assertThat(orderStatus(order.id())).isEqualTo("CANCEL_REQUESTED");
        verify(stockRestorationPort, never()).restoreCancellation(anyLong(), anyLong(), any());
    }

    // ---- 반품 승인 · 거절 (#17 B2c-2) ----

    private static final String RETURN_REASON = "검수 결과 사용 흔적이 있어 반품 불가";

    /** 결제 완료 주문(10,000 × 2 + 배송비 3,000 = 23,000)을 배송 완료 뒤 반품 요청으로 두고 1개 변심 반품 접수(REQUESTED)를 만든다. */
    private Claim requestedReturn(Order order) {
        jdbc.update("update orders.orders set status = 'RETURN_REQUESTED', delivered_at = ?, version = version + 1 where id = ?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(3_600)), order.id());
        Claim requested = new Claim(ids.generateId(), order.id(), UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(ids.generateId(), order.itemIds().getFirst(), 1, ClaimItem.Reason.CHANGE_OF_MIND)), null, null);
        return new TransactionTemplate(transactionManager).execute(status -> claimRepository.insert(requested));
    }

    private Map<String, Object> claimRow(long claimId) {
        return jdbc.queryForMap("select status, expected_refund_amount, return_fault, restock, deduction_amount, decision_reason, version from orders.claim "
            + "where id = ?", claimId);
    }

    private MvcTestResult approveReturn(long claimId, long expectedVersion, long expectedRefund, MemberRole role) {
        return mvc.post().uri("/api/v1/admin/claims/{claimId}/return-approval", claimId).header(HttpHeaders.AUTHORIZATION, bearer(1, role))
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":" + expectedVersion + ",\"fault\":\"CUSTOMER\",\"restock\":true,"
                + "\"deductionAmount\":{\"amount\":1000,\"currency\":\"KRW\"},\"expectedRefundAmount\":{\"amount\":" + expectedRefund + ",\"currency\":\"KRW\"}}")
            .exchange();
    }

    @Test
    @DisplayName("반품 승인 — 미리보기 금액 − 차감액으로 승인하면 같은 요청에서 환불 · 재고 복원까지 이어 완료되고 주문은 배송 완료로 돌아간다. 금액이 다르면 409 ORDER_046")
    void returnApprovalRefundsAndRestores() {
        Order order = paidOrder();
        Claim requested = requestedReturn(order);

        MvcTestResult before = detail(requested.id());
        assertThat(before).bodyJson().extractingPath("$.dataBody.returnDecision.approvable").isEqualTo(true);
        // 남는 10,000 은 무료배송 기준 미만이라 배송비 3,000 을 그대로 둔다 — 고객 귀책 23,000 − 13,000 = 10,000
        assertThat(before).bodyJson().extractingPath("$.dataBody.returnDecision.refundByFault[0].amount.amount").isEqualTo(10_000);
        assertThat(before).bodyJson().extractingPath("$.dataBody.returnDecision.reason").as("재시도 스케줄러가 켜져 있어 REFUND_NOT_OPEN 이 아니다").isNull();
        assertThat(approveReturn(requested.id(), 0, 9_000, MemberRole.STAFF)).hasStatus(403);

        MvcTestResult changed = approveReturn(requested.id(), 0, 10_000, MemberRole.MANAGER);
        assertThat(changed).hasStatus(409);
        assertThat(changed).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_046");
        assertThat(claimRow(requested.id())).containsEntry("status", "REQUESTED");

        MvcTestResult approved = approveReturn(requested.id(), 0, 9_000, MemberRole.MANAGER);

        assertThat(approved).hasStatus(200);
        assertThat(approved).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("COMPLETED");
        assertThat(approved).bodyJson().extractingPath("$.dataBody.returnApproval.fault.code").isEqualTo("CUSTOMER");
        assertThat(approved).bodyJson().extractingPath("$.dataBody.returnApproval.deduction.amount").isEqualTo(1_000);
        assertThat(approved).bodyJson().extractingPath("$.dataBody.returnDecision").isNull();
        assertThat(claimRow(requested.id())).containsEntry("status", "COMPLETED").containsEntry("return_fault", "CUSTOMER").containsEntry("restock", true)
            .hasEntrySatisfying("expected_refund_amount", amount -> assertThat(((Number) amount).longValue()).isEqualTo(9_000));
        verify(paymentCancelPort, times(1)).cancel(anyLong(), eq(requested.id()), eq(com.commercefoundry.shared.commerce.Money.of(9_000,
            com.commercefoundry.shared.commerce.CurrencyCode.KRW)));
        verify(stockRestorationPort, times(1)).restoreReturn(eq(requested.id()), anyLong(), any());
        assertThat(orderStatus(order.id())).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("반품 거절 — 환불 · 복원 없이 REJECTED, 주문은 배송 완료로 돌아가고 ADMIN 이력에 사유가 남는다. 같은 화면으로 다시 보내면 409 ORDER_037")
    void returnRejectionSettlesOrder() {
        Order order = paidOrder();
        Claim requested = requestedReturn(order);
        String body = "{\"expectedVersion\":0,\"reason\":\"" + RETURN_REASON + "\"}";

        MvcTestResult rejected = mvc.post().uri("/api/v1/admin/claims/{claimId}/return-rejection", requested.id())
            .header(HttpHeaders.AUTHORIZATION, bearer(1, MemberRole.MANAGER)).contentType(MediaType.APPLICATION_JSON).content(body).exchange();

        assertThat(rejected).hasStatus(200);
        assertThat(rejected).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("REJECTED");
        assertThat(rejected).bodyJson().extractingPath("$.dataBody.recovery.lastDecision.reason").isEqualTo(RETURN_REASON);
        assertThat(claimRow(requested.id())).containsEntry("status", "REJECTED").containsEntry("decision_reason", RETURN_REASON);
        assertThat(orderStatus(order.id())).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForMap("select from_status, changed_by_type, reason from orders.order_status_history where order_id = ? and to_status = 'DELIVERED'",
            order.id())).containsEntry("from_status", "RETURN_REQUESTED").containsEntry("changed_by_type", "ADMIN").containsEntry("reason", RETURN_REASON);
        verify(paymentCancelPort, never()).cancel(anyLong(), anyLong(), any());
        verify(stockRestorationPort, never()).restoreReturn(anyLong(), anyLong(), any());

        MvcTestResult replay = mvc.post().uri("/api/v1/admin/claims/{claimId}/return-rejection", requested.id())
            .header(HttpHeaders.AUTHORIZATION, bearer(1, MemberRole.MANAGER)).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        assertThat(replay).hasStatus(409);
        assertThat(replay).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_037");
    }

    // ---- 결제 후 취소 거절 (#17 A4) ----

    private static final String REJECTION_REASON = "결제사 화면에서 환불 없음 확인, 고객과 통화해 출고 진행";

    private void voidAnswers(PaymentCancelVoidQueryResult.Outcome outcome, String reasonCode) {
        when(paymentCancelPort.confirmNoRefund(anyLong(), anyLong(), anyLong(), any())).thenReturn(new PaymentCancelVoidQueryResult(outcome, reasonCode));
    }

    private MvcTestResult rejectCancellation(long claimId, long expectedVersion, MemberRole role) {
        return mvc.post().uri("/api/v1/admin/claims/{claimId}/cancellation-rejection", claimId).header(HttpHeaders.AUTHORIZATION, bearer(1, role))
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":" + expectedVersion + ",\"reason\":\"" + REJECTION_REASON + "\"}").exchange();
    }

    private long paymentIdOf(long orderId) {
        return jdbc.queryForObject("select payment_id from orders.order_payment where order_id = ? and result = 'PAID'", Long.class, orderId);
    }

    /**
     * 취소 요청 전 상태({@code before})에서 결제사가 거절한 취소를 만든다. 결제 완료 · 상품 준비중은 그 상태에서 고객이 취소하고, 제작 중은 이 컨텍스트가
     * 제작 중 취소를 열지 않아(설정 기본값) 결제 완료에서 취소한 뒤 이력의 직전 상태만 제작 중으로 바꾼다 — 거절은 주문이 아니라 이력 최신 행으로 되돌릴 상태를 정한다.
     */
    private long rejectedRefundFrom(Order order, OrderStatus before) {
        if (before == OrderStatus.PREPARING) {
            jdbc.update("update orders.orders set status = 'PREPARING', version = version + 1 where id = ?", order.id());
        }
        long claimId = rejectedRefundClaim(order);
        if (before == OrderStatus.MAKING) {
            jdbc.update("update orders.order_status_history set from_status = 'MAKING' where order_id = ? and to_status = 'CANCEL_REQUESTED'", order.id());
        }
        return claimId;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = OrderStatus.class, names = {"PAID", "PREPARING", "MAKING"})
    @DisplayName("취소 거절 — 결제사가 환불 없음을 확정하면 클레임 REJECTED, 주문은 이력의 직전 상태로 돌아가고 ADMIN 이력에 사유가 남는다. 환불 · 재고 복원은 부르지 않는다")
    void cancellationRejectionRestoresPreviousStatus(OrderStatus before) {
        Order order = paidOrder();
        long claimId = rejectedRefundFrom(order, before);
        voidAnswers(PaymentCancelVoidQueryResult.Outcome.NO_REFUND_CONFIRMED, "CLAIM_REJECTED_BY_PG");
        MvcTestResult detail = detail(claimId);
        assertThat(detail).bodyJson().extractingPath("$.dataBody.recovery.cancellationRejectable").isEqualTo(true);
        verify(paymentCancelPort, never()).confirmNoRefund(anyLong(), anyLong(), anyLong(), any());

        MvcTestResult rejected = rejectCancellation(claimId, version(order.id()), MemberRole.OWNER);

        assertThat(rejected).hasStatus(200);
        assertThat(rejected).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("REJECTED");
        assertThat(rejected).bodyJson().extractingPath("$.dataBody.recovery.situation.code").isEqualTo("NO_ACTION");
        assertThat(rejected).bodyJson().extractingPath("$.dataBody.recovery.cancellationRejectable").isEqualTo(false);
        assertThat(rejected).bodyJson().extractingPath("$.dataBody.recovery.refundConfirmable").isEqualTo(false);
        assertThat(rejected).bodyJson().extractingPath("$.dataBody.recovery.lastDecision.reason").isEqualTo(REJECTION_REASON);
        assertThat(rejected.getResponse().getContentAsByteArray()).asString(java.nio.charset.StandardCharsets.UTF_8)
            .doesNotContain("PAYMENT_019").doesNotContain("decidedBy").doesNotContain("CLAIM_REJECTED_BY_PG");
        assertThat(orderStatus(order.id())).isEqualTo(before.name());
        assertThat(jdbc.queryForMap("select to_status, changed_by_type, changed_by_id, reason from orders.order_status_history "
            + "where order_id = ? and from_status = 'CANCEL_REQUESTED'", order.id()))
            .containsEntry("to_status", before.name()).containsEntry("changed_by_type", "ADMIN").containsEntry("reason", REJECTION_REASON)
            .hasEntrySatisfying("changed_by_id", id -> assertThat(((Number) id).longValue()).isEqualTo(1L));
        assertThat(claim(order.id())).containsEntry("status", "REJECTED").containsEntry("failure_code", null).containsEntry("decision_reason", REJECTION_REASON)
            .containsEntry("decision_failure_code", "PAYMENT_019");
        verify(paymentCancelPort, times(1)).confirmNoRefund(paymentIdOf(order.id()), order.id(), claimId, order.quote().totalAmount());
        verify(paymentCancelPort, times(1)).cancel(anyLong(), anyLong(), any());
        verify(stockRestorationPort, never()).restoreCancellation(anyLong(), anyLong(), any());
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"REFUNDED, 409, ORDER_052", "UNCONFIRMED, 409, ORDER_053", "UNAVAILABLE, 503, ORDER_054"})
    @DisplayName("취소 거절 — 결제사가 환불 없음을 확정하지 않으면(이미 환불 052 · 확인 못 함 053 · 확인 불가 054) 클레임 · 주문 · 이력이 그대로다")
    void unconfirmedNoRefundChangesNothing(PaymentCancelVoidQueryResult.Outcome outcome, int status, String code) {
        Order order = paidOrder();
        long claimId = rejectedRefundClaim(order);
        voidAnswers(outcome, "PG_ANSWER");
        Map<String, Object> claimBefore = claim(order.id());
        long orderVersionBefore = orderVersion(order.id());
        long historyBefore = historyCount(order.id());

        MvcTestResult result = rejectCancellation(claimId, version(order.id()), MemberRole.OWNER);

        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(claim(order.id())).isEqualTo(claimBefore);
        assertThat(orderStatus(order.id())).isEqualTo("CANCEL_REQUESTED");
        assertThat(orderVersion(order.id())).isEqualTo(orderVersionBefore);
        assertThat(historyCount(order.id())).isEqualTo(historyBefore);
        verify(paymentCancelPort, times(1)).confirmNoRefund(anyLong(), anyLong(), anyLong(), any());
        verify(stockRestorationPort, never()).restoreCancellation(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("취소 거절 — 결과 미확인 환불 실패(REFUND_UNCONFIRMED)는 후보가 아니다: cancellationRejectable=false, 409 ORDER_041 이고 무환불 판정을 부르지 않는다")
    void unconfirmedRefundIsNotRejectableAndNeverCallsVoid() {
        Order order = paidOrder();
        // 결제사 결과를 끝내 확인하지 못한 취소 — 주문은 취소 요청에 머물고 클레임은 재시도 상한 뒤 REFUND_UNCONFIRMED 로 닫힌 것과 같게 둔다
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.unknown());
        customerCancel(order.id());
        jdbc.update("update orders.claim set status = 'REFUND_FAILED', failure_code = 'REFUND_UNCONFIRMED', next_attempt_at = null, "
            + "version = version + 1 where order_id = ? and type = 'CANCELLATION'", order.id());
        assertThat(orderStatus(order.id())).isEqualTo("CANCEL_REQUESTED");
        long claimId = claimId(order.id());
        long before = version(order.id());

        assertThat(detail(claimId)).bodyJson().extractingPath("$.dataBody.recovery.cancellationRejectable").isEqualTo(false);
        MvcTestResult result = rejectCancellation(claimId, before, MemberRole.OWNER);

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_041");
        assertThat(version(order.id())).isEqualTo(before);
        verify(paymentCancelPort, never()).confirmNoRefund(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("취소 거절 — 주문 저장이 낙관락에서 지면 409 ORDER_037 이고 같은 트랜잭션의 클레임 거절도 롤백된다 — 클레임 · 주문 · 이력이 그대로다")
    void cancellationRejectionOrderConflictRollsBackClaim() {
        Order order = paidOrder();
        long claimId = rejectedRefundClaim(order);
        voidAnswers(PaymentCancelVoidQueryResult.Outcome.NO_REFUND_CONFIRMED, "CLAIM_REJECTED_BY_PG");
        Map<String, Object> claimBefore = claim(order.id());
        long orderVersionBefore = orderVersion(order.id());
        long historyBefore = historyCount(order.id());
        // 클레임 저장 직후 · 주문 저장 직전에 같은 트랜잭션 연결로 주문 버전을 올린다 — 주문 저장의 버전 비교가 실제로 진다(확정 시험과 같은 방식)
        doAnswer(invocation -> {
            Object saved = invocation.callRealMethod();
            jdbc.update("update orders.orders set version = version + 1 where id = ?", order.id());
            return saved;
        }).when(org.springframework.test.util.AopTestUtils.<ClaimRepositoryPort>getUltimateTargetObject(claimRepository)).update(any());

        MvcTestResult result = rejectCancellation(claimId, version(order.id()), MemberRole.OWNER);

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_037");
        assertThat(claim(order.id())).isEqualTo(claimBefore).containsEntry("status", "REFUND_FAILED").containsEntry("decision_reason", null);
        assertThat(orderStatus(order.id())).isEqualTo("CANCEL_REQUESTED");
        assertThat(orderVersion(order.id())).as("같은 트랜잭션에서 올린 버전도 롤백된다").isEqualTo(orderVersionBefore);
        assertThat(historyCount(order.id())).isEqualTo(historyBefore);
    }

    @Test
    @DisplayName("취소 거절 — 같은 화면으로 다시 보내면 409 ORDER_037 이고 무환불 판정을 다시 부르지 않는다. 거절된 접수는 다시 시도 038 · 환불 확정 041 이다")
    void rejectedCancellationIsFinal() {
        Order order = paidOrder();
        long claimId = rejectedRefundClaim(order);
        voidAnswers(PaymentCancelVoidQueryResult.Outcome.NO_REFUND_CONFIRMED, "CLAIM_REJECTED_BY_PG");
        long seen = version(order.id());
        assertThat(rejectCancellation(claimId, seen, MemberRole.ADMIN)).hasStatus(200);

        MvcTestResult replay = rejectCancellation(claimId, seen, MemberRole.ADMIN);
        assertThat(replay).hasStatus(409);
        assertThat(replay).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_037");
        verify(paymentCancelPort, times(1)).confirmNoRefund(anyLong(), anyLong(), anyLong(), any());

        long now = version(order.id());
        MvcTestResult retry = mvc.post().uri("/api/v1/admin/claims/{claimId}/retries", claimId).header(HttpHeaders.AUTHORIZATION, bearer(1, MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":" + now + "}").exchange();
        assertThat(retry).hasStatus(409);
        assertThat(retry).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_038");
        MvcTestResult confirm = decide("refund-confirmation", claimId, now, MemberRole.OWNER);
        assertThat(confirm).hasStatus(409);
        assertThat(confirm).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_041");
        MvcTestResult again = rejectCancellation(claimId, now, MemberRole.OWNER);
        assertThat(again).hasStatus(409);
        assertThat(again).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_041");
        assertThat(claim(order.id())).containsEntry("status", "REJECTED");
        assertThat(orderStatus(order.id())).isEqualTo("PAID");
        verify(paymentCancelPort, times(1)).confirmNoRefund(anyLong(), anyLong(), anyLong(), any());
        verify(stockRestorationPort, never()).restoreCancellation(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("취소 거절 — 반품 거절 · 다시 시도는 되는 MANAGER 도 실제 보안 체인에서 403 이고 무환불 판정 · 클레임 · 주문이 그대로다")
    void managerMayNotRejectCancellation() {
        Order order = paidOrder();
        long claimId = rejectedRefundClaim(order);
        long before = version(order.id());

        assertThat(rejectCancellation(claimId, before, MemberRole.MANAGER)).hasStatus(403);
        assertThat(rejectCancellation(claimId, before, MemberRole.STAFF)).hasStatus(403);

        assertThat(version(order.id())).isEqualTo(before);
        assertThat(orderStatus(order.id())).isEqualTo("CANCEL_REQUESTED");
        verify(paymentCancelPort, never()).confirmNoRefund(anyLong(), anyLong(), anyLong(), any());
    }
}
