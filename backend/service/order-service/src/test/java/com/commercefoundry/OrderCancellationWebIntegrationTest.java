package com.commercefoundry;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationPipelineProcessor;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentCancelPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelVoidQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.security.support.TestTokens;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 후 고객 취소 (#16 PR-2) — 실제 JWT · 권한 · Facade · 단계별 트랜잭션 · H2 를 관통하고 payment · product 호출만 스텁한다. 결제 전 취소 경로 회귀도 본다.
 *
 * <p>취소와 맞물리는 송장 확정(출고 반영 internal API)의 경합 · 재시도도 여기서 본다(#363) — 같은 컨텍스트를 쓰려고 별도 클래스를 만들지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
// 결제 후 취소는 재시도가 켜져야 열린다(H1). 스케줄러가 시험 중에 돌지 않게 주기를 길게 둔다
@TestPropertySource(properties = {"order.cancellation.retry.enabled=true", "order.cancellation.retry.interval=1h"})
class OrderCancellationWebIntegrationTest {

    @Autowired MockMvcTester mvc;
    @Autowired ThreadPoolTaskScheduler taskScheduler;
    @Autowired OrderRepositoryPort orders;
    @Autowired OrderPaymentRepositoryPort payments;
    @Autowired SnowflakeIdGenerator ids;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean PaymentCancelPort paymentCancelPort;
    @MockitoBean StockRestorationPort stockRestorationPort;
    @MockitoSpyBean OrderCancellationPipelineProcessor pipeline;
    /** 이 시험은 쓰지 않는다 — {@code ClaimAdminRetryWebIntegrationTest} 와 빈 구성을 같게 둬 Spring 컨텍스트를 공유한다(CI 메모리, #17). */
    @MockitoSpyBean ClaimRepositoryPort claimRepository;

    @BeforeEach
    void succeedByDefault() {
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.succeeded());
    }

    /** 회원 주문을 넣고 상태를 바꾼다. 결제 뒤 상태면 결제 완료 기록(승인 금액 = 총액)도 넣는다. */
    Order create(OrderStatus status, long paymentId) {
        Order order = Order.place(ids.generateId(), TestTokens.MEMBER_ID, domesticQuote(quoteLine(101, 10_000, 2), quoteLine(102, 5_000, 1)),
            ids::generateId, address("KR"), ids.generateId(), Instant.now().plusSeconds(600));
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            orders.insert(order, OrderStatusHistory.created(ids.generateId(), order));
            jdbc.update("update orders.orders set status = ?, version = version + 1 where id = ?", status.name(), order.id());
            if (paymentId > 0) {
                Order paid = new Order(order.id(), order.memberId(), OrderStatus.PAID, order.quote(), order.itemIds(), order.shippingAddress(),
                    order.stockReservationId(), order.paymentDueAt(), null, null, null, 1L);
                payments.insert(OrderPayment.paid(ids.generateId(), paid, paymentId, order.quote().totalAmount(), Instant.now(), UUID.randomUUID()));
            }
        });
        return order;
    }

    MvcTestResult cancel(long orderId, long member) {
        String token = TestTokens.issuer(Instant.now()).issueAccessToken(member, MemberRole.CUSTOMER).tokenValue();
        return mvc.post().uri("/api/v1/orders/{orderId}/cancellation", orderId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ORDER_MISTAKE\"}").exchange();
    }

    Map<String, Object> claim(long orderId) {
        return jdbc.queryForMap("select id, status, attempt_count, payment_id, failure_code from orders.claim where order_id = ? and type = 'CANCELLATION'", orderId);
    }

    @Test
    void paidOrderIsRefundedRestoredAndCanceledInOneRequest() {
        long paymentId = ids.generateId();
        Order order = create(OrderStatus.PAID, paymentId);

        var result = cancel(order.id(), TestTokens.MEMBER_ID);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("CANCELED");
        assertThat(result).bodyJson().extractingPath("$.dataBody.cancelable").isEqualTo(false);
        Map<String, Object> claim = claim(order.id());
        assertThat(claim.get("status")).isEqualTo("COMPLETED");
        assertThat(((Number) claim.get("payment_id")).longValue()).isEqualTo(paymentId);
        long claimId = ((Number) claim.get("id")).longValue();
        verify(paymentCancelPort).cancel(paymentId, claimId, order.quote().totalAmount());
        verify(stockRestorationPort).restoreCancellation(claimId, order.stockReservationId(), new TreeMap<>(Map.of(101L, 2, 102L, 1)));
        assertThat(jdbc.queryForList("select to_status from orders.order_status_history where order_id = ? order by id", String.class, order.id()))
            .containsSubsequence("CANCEL_REQUESTED", "CANCELED");
    }

    @Test
    void confirmedRefundRecordingFailureIs503AndSameCustomerRequestDoesNotCreateAnotherClaim() {
        Order order = create(OrderStatus.PAID, ids.generateId());
        doThrow(new OrderException(OrderErrorCode.CANCELLATION_REFUND_RECORDING_UNAVAILABLE)).when(pipeline).progress(any());
        var failed = cancel(order.id(), TestTokens.MEMBER_ID);
        assertThat(failed).hasStatus(503);
        assertThat(failed).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_036");
        long claimId = ((Number) claim(order.id()).get("id")).longValue();
        assertThat(cancel(order.id(), TestTokens.MEMBER_ID)).hasStatus(200);
        assertThat(((Number) claim(order.id()).get("id")).longValue()).isEqualTo(claimId);
        assertThat(jdbc.queryForObject("select count(*) from orders.claim where order_id = ?", Long.class, order.id())).isEqualTo(1);
        verify(pipeline, times(1)).progress(any());
        verifyNoInteractions(paymentCancelPort, stockRestorationPort);
    }

    @Test
    void unrelatedPipelineRuntimeKeepsExistingAcceptedCancellationContract() {
        Order order = create(OrderStatus.PAID, ids.generateId());
        doThrow(new IllegalStateException("unrelated failure")).when(pipeline).progress(any());
        assertThat(cancel(order.id(), TestTokens.MEMBER_ID)).hasStatus(200);
        assertThat(claim(order.id())).containsEntry("status", "REQUESTED");
    }

    @Test
    void unconfirmedRefundAnswersCancelRequestedAndDoubleRequestReplaysWithoutCallingAgain() {
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.unknown());
        Order order = create(OrderStatus.PREPARING, ids.generateId());

        var first = cancel(order.id(), TestTokens.MEMBER_ID);
        var second = cancel(order.id(), TestTokens.MEMBER_ID);

        assertThat(first).hasStatus(200);
        assertThat(first).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("CANCEL_REQUESTED");
        assertThat(second).hasStatus(200);
        assertThat(second).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("CANCEL_REQUESTED");
        assertThat(claim(order.id())).containsEntry("status", "REQUESTED");
        assertThat(((Number) claim(order.id()).get("attempt_count")).intValue()).isEqualTo(1);
        verify(paymentCancelPort, times(1)).cancel(anyLong(), anyLong(), any());
        verifyNoInteractions(stockRestorationPort);
        assertThat(jdbc.queryForObject("select count(*) from orders.claim where order_id = ?", Long.class, order.id())).isEqualTo(1L);
    }

    @Test
    void rejectedRefundKeepsCancelRequestedAndTerminalRestorationKeepsCanceled() throws Exception {
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.rejected("PAYMENT_016"));
        Order rejected = create(OrderStatus.PAID, ids.generateId());
        assertThat(cancel(rejected.id(), TestTokens.MEMBER_ID)).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("CANCEL_REQUESTED");
        assertThat(claim(rejected.id())).containsEntry("status", "REFUND_FAILED").containsEntry("failure_code", "PAYMENT_016");

        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.succeeded());
        doThrow(new StockReservationException(StockReservationException.Failure.QUANTITY_EXCEEDED))
            .when(stockRestorationPort).restoreCancellation(anyLong(), anyLong(), any());
        Order restorationFailed = create(OrderStatus.PAID, ids.generateId());
        assertThat(cancel(restorationFailed.id(), TestTokens.MEMBER_ID)).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("CANCELED");
        assertThat(claim(restorationFailed.id())).containsEntry("status", "RESTORATION_FAILED").containsEntry("failure_code", "PRODUCT_028");
    }

    /**
     * #17 A4 — 운영자가 무환불 확인 뒤 거절한 취소가 있는 주문은 고객이 다시 취소해도 409 ORDER_055 다(결정적 키 충돌 ORDER_025 가 아니다). 새 클레임 · 환불 호출이 없고,
     * 고객 클레임 목록에는 상태 "거절"만 보이고 사유는 보이지 않는다. 거절은 실제 어드민 API(대표 토큰 · 무환불 판정 스텁)로 만든다.
     */
    @Test
    void customerCannotCancelAgainAfterAdminRejectedCancellation() {
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.rejected("PAYMENT_019"));
        Order order = create(OrderStatus.PAID, ids.generateId());
        assertThat(cancel(order.id(), TestTokens.MEMBER_ID)).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("CANCEL_REQUESTED");
        long claimId = ((Number) claim(order.id()).get("id")).longValue();
        long version = jdbc.queryForObject("select version from orders.claim where id = ?", Long.class, claimId);
        when(paymentCancelPort.confirmNoRefund(anyLong(), anyLong(), anyLong(), any()))
            .thenReturn(new PaymentCancelVoidQueryResult(PaymentCancelVoidQueryResult.Outcome.NO_REFUND_CONFIRMED, "CLAIM_REJECTED_BY_PG"));
        String reason = "결제사 화면에서 환불 없음 확인";
        String owner = TestTokens.issuer(Instant.now()).issueAccessToken(1, MemberRole.OWNER).tokenValue();
        assertThat(mvc.post().uri("/api/v1/admin/claims/{claimId}/cancellation-rejection", claimId).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":" + version + ",\"reason\":\"" + reason + "\"}").exchange()).hasStatus(200);
        assertThat(jdbc.queryForObject("select status from orders.orders where id = ?", String.class, order.id())).isEqualTo("PAID");

        assertFailure(cancel(order.id(), TestTokens.MEMBER_ID), 409, "ORDER_055");

        assertThat(jdbc.queryForObject("select count(*) from orders.claim where order_id = ?", Long.class, order.id())).isEqualTo(1L);
        assertThat(claim(order.id())).containsEntry("status", "REJECTED");
        assertThat(jdbc.queryForObject("select status from orders.orders where id = ?", String.class, order.id())).isEqualTo("PAID");
        verify(paymentCancelPort, times(1)).cancel(anyLong(), anyLong(), any());
        verifyNoInteractions(stockRestorationPort);
        String token = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
        var claims = mvc.get().uri("/api/v1/orders/{orderId}/claims", order.id()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
        assertThat(claims).bodyJson().extractingPath("$.dataBody[0].status.code").isEqualTo("REJECTED");
        assertThat(claims.getResponse().getContentAsByteArray()).asString(java.nio.charset.StandardCharsets.UTF_8).doesNotContain(reason);
    }

    @Test
    void othersShippingMakingAndMissingPaymentAreRejectedWithoutClaims() {
        Order paid = create(OrderStatus.PAID, ids.generateId());
        Order shipping = create(OrderStatus.SHIPPING, ids.generateId());
        Order making = create(OrderStatus.MAKING, ids.generateId());
        Order withoutPayment = create(OrderStatus.PAID, 0);

        assertFailure(cancel(paid.id(), TestTokens.MEMBER_ID + 1), 404, "ORDER_001");
        assertFailure(cancel(shipping.id(), TestTokens.MEMBER_ID), 409, "ORDER_020");
        assertFailure(cancel(making.id(), TestTokens.MEMBER_ID), 409, "ORDER_020");
        assertFailure(cancel(withoutPayment.id(), TestTokens.MEMBER_ID), 500, "ORDER_033");

        for (Order order : new Order[]{paid, shipping, making, withoutPayment}) {
            assertThat(jdbc.queryForObject("select count(*) from orders.claim where order_id = ?", Long.class, order.id())).isZero();
        }
        assertThat(jdbc.queryForObject("select status from orders.orders where id = ?", String.class, withoutPayment.id())).isEqualTo("PAID");
        verifyNoInteractions(paymentCancelPort, stockRestorationPort);
    }

    @Test
    void pendingPaymentCancelKeepsBeforePaymentPath() {
        Order pending = create(OrderStatus.PENDING_PAYMENT, 0);

        var result = cancel(pending.id(), TestTokens.MEMBER_ID);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("select count(*) from orders.claim where order_id = ?", Long.class, pending.id())).isZero();
        assertThat(cancel(pending.id(), TestTokens.MEMBER_ID)).hasStatus(200);
        verifyNoInteractions(paymentCancelPort, stockRestorationPort);
    }

    @Test
    void detailAndListExposePaidOrderAsCancelable() {
        Order paid = create(OrderStatus.PAID, ids.generateId());
        String token = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();

        var detail = mvc.get().uri("/api/v1/orders/{orderId}", paid.id()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();

        assertThat(detail).hasStatus(200);
        assertThat(detail).bodyJson().extractingPath("$.dataBody.cancelable").isEqualTo(true);
        var list = mvc.get().uri("/api/v1/orders?size=100").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
        assertThat(list).bodyJson().extractingPath("$.dataBody.contents[?(@.orderId=='" + paid.id() + "')].cancelable").asArray().containsExactly(true);
    }

    /** delivery-service 가 송장 확정 때 부르는 출고 반영 internal API (#18) — 실제 서비스 토큰(aud = order-service)을 싣는다. */
    MvcTestResult ship(long orderId, Instant shippedAt) {
        return mvc.post().uri("/internal/v1/orders/{orderId}/shipment", orderId)
            .header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer("delivery-service", "order-service"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deliveryId\":\"" + ids.generateId() + "\",\"shippedAt\":\"" + shippedAt + "\"}").exchange();
    }

    long shipmentHistoryCount(long orderId) {
        return jdbc.queryForObject("select count(*) from orders.order_status_history where order_id = ? and to_status = 'SHIPPING'", Long.class, orderId);
    }

    /** #363 — 포장 검수 뒤 고객 취소가 먼저 들어오면, 뒤늦은 송장 확정은 주문을 다시 읽어 409 로 거절되고 취소 상태 · 출고 시각을 건드리지 않는다. */
    @Test
    void shipmentAfterCustomerCancellationIsRejectedAndKeepsCancellation() {
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.unknown());
        Order requested = create(OrderStatus.PREPARING, ids.generateId());
        assertThat(cancel(requested.id(), TestTokens.MEMBER_ID)).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("CANCEL_REQUESTED");
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.succeeded());
        Order canceled = create(OrderStatus.PREPARING, ids.generateId());
        assertThat(cancel(canceled.id(), TestTokens.MEMBER_ID)).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("CANCELED");

        assertFailure(ship(requested.id(), Instant.now()), 409, "ORDER_020");
        assertFailure(ship(canceled.id(), Instant.now()), 409, "ORDER_020");

        assertThat(jdbc.queryForMap("select status, shipped_at from orders.orders where id = ?", requested.id()))
            .containsEntry("status", "CANCEL_REQUESTED").containsEntry("shipped_at", null);
        assertThat(jdbc.queryForMap("select status, shipped_at from orders.orders where id = ?", canceled.id()))
            .containsEntry("status", "CANCELED").containsEntry("shipped_at", null);
        assertThat(shipmentHistoryCount(requested.id())).isZero();
        assertThat(shipmentHistoryCount(canceled.id())).isZero();
    }

    /**
     * #363 — 출고 반영이 주문을 읽은 뒤 고객 취소가 먼저 커밋하는 경합. {@code OrderFulfillmentProcessor} 의 읽기 → {@code Order.ship} → 저장 순서를
     * 그대로 재현해, 낡은 버전의 출고 저장이 버전 비교로 실패하고 취소가 남는지 본다. 이어진 재시도도 새로 읽어 409 로 거절된다.
     */
    @Test
    void staleShipmentCannotOverwriteCancellationCommittedAfterRead() {
        Order order = create(OrderStatus.PREPARING, ids.generateId());
        Order readByShipment = orders.findById(order.id()).orElseThrow();

        assertThat(cancel(order.id(), TestTokens.MEMBER_ID)).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("CANCELED");

        Order shipped = readByShipment.ship(Instant.now());
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> orders.saveStatusChange(shipped,
            OrderStatusHistory.changedByService(ids.generateId(), readByShipment, shipped, OrderStatusHistory.SHIPMENT_REGISTERED))))
            .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(jdbc.queryForMap("select status, shipped_at from orders.orders where id = ?", order.id()))
            .containsEntry("status", "CANCELED").containsEntry("shipped_at", null);
        assertThat(shipmentHistoryCount(order.id())).isZero();

        assertFailure(ship(order.id(), Instant.now()), 409, "ORDER_020");
        assertThat(shipmentHistoryCount(order.id())).isZero();
    }

    /**
     * #363 — 같은 송장 확정을 다시 보내도(delivery 재시도 · 다른 운영자의 재등록) 출고는 한 번만 반영된다: 이력 한 줄, 첫 출고 시각 유지, 재고 · 결제 호출 없음.
     * 재고는 주문 생성 때 선점 · 차감되고 출고 반영은 재고를 부르지 않으므로 재시도가 재고를 다시 줄일 길이 없다.
     */
    @Test
    void repeatedShipmentIsAppliedOnceWithoutStockOrPaymentCalls() {
        Order order = create(OrderStatus.PREPARING, ids.generateId());
        Instant firstShippedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(60);

        var first = ship(order.id(), firstShippedAt);
        var retried = ship(order.id(), firstShippedAt.plusSeconds(30));

        assertThat(first).hasStatus(200);
        assertThat(first).bodyJson().extractingPath("$.dataBody.status").isEqualTo("SHIPPING");
        assertThat(retried).hasStatus(200);
        assertThat(retried).bodyJson().extractingPath("$.dataBody.status").isEqualTo("SHIPPING");
        assertThat(shipmentHistoryCount(order.id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select shipped_at from orders.orders where id = ?", Timestamp.class, order.id()).toInstant()).isEqualTo(firstShippedAt);
        verifyNoInteractions(paymentCancelPort, stockRestorationPort);
    }

    /** 미결제 만료 · 자동 구매 확정 · 취소 재시도 · 결제 반영 재고 복원이 서로 막지 않게 스케줄링 스레드가 여럿이다(application.yml {@code spring.task.scheduling.pool.size}). */
    @Test
    void schedulingPoolHasRoomForEachScheduler() {
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isGreaterThanOrEqualTo(4);
    }

    private static void assertFailure(MvcTestResult result, int status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
    }
}
