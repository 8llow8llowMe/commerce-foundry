package com.commercefoundry;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationPipelineProcessor;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentCancelPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

    /** 미결제 만료 · 자동 구매 확정 · 취소 재시도가 서로 막지 않게 스케줄링 스레드가 여럿이다(application.yml {@code spring.task.scheduling.pool.size}). */
    @Test
    void schedulingPoolHasRoomForEachScheduler() {
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isGreaterThanOrEqualTo(3);
    }

    private static void assertFailure(MvcTestResult result, int status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
    }
}
