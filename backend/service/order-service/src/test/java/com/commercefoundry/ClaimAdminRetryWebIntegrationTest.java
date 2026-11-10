package com.commercefoundry;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationPipelineProcessor;
import com.commercefoundry.domainlayer.order.application.port.in.ClaimCancellationRetrySchedulerUseCase;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * 어드민 결제 후 취소 다시 시도 (#17 A2b) — 실제 JWT · 권한 · Facade · 단계별 트랜잭션 · H2 를 관통하고 payment · product 호출만 스텁한다.
 * 스텁은 실제 PG 환불의 증거가 아니다. 테스트 값은 모두 가짜다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
// 결제 후 취소 · 다시 시도는 재시도가 켜져야 열린다. 스케줄러가 시험 중에 돌지 않게 주기를 길게 둔다
@TestPropertySource(properties = {"order.cancellation.retry.enabled=true", "order.cancellation.retry.interval=1h"})
class ClaimAdminRetryWebIntegrationTest {

    @Autowired MockMvcTester mvc;
    @Autowired OrderRepositoryPort orders;
    @Autowired OrderPaymentRepositoryPort payments;
    @Autowired SnowflakeIdGenerator ids;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean PaymentCancelPort paymentCancelPort;
    @MockitoBean StockRestorationPort stockRestorationPort;
    /** 쓰지 않지만 {@code OrderCancellationWebIntegrationTest} 와 빈 구성을 같게 둬 Spring 컨텍스트 캐시를 공유한다 — 컨텍스트가 늘면 CI 에서 메모리가 모자란다. */
    @MockitoSpyBean OrderCancellationPipelineProcessor pipeline;
    /** 경합 창을 만드는 데 쓴다. {@code OrderCancellationWebIntegrationTest} 도 같은 spy 를 둬 컨텍스트를 공유한다. */
    @MockitoSpyBean ClaimRepositoryPort claimRepository;
    @Autowired ClaimCancellationRetrySchedulerUseCase retryScheduler;

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
        return jdbc.queryForMap("select id, status, attempt_count, failure_code, version from orders.claim where order_id = ? and type = 'CANCELLATION'",
            orderId);
    }

    private long claimId(long orderId) {
        return ((Number) claim(orderId).get("id")).longValue();
    }

    private long version(long orderId) {
        return ((Number) claim(orderId).get("version")).longValue();
    }

    private MvcTestResult detail(long claimId) {
        return mvc.get().uri("/api/v1/admin/claims/{claimId}", claimId).header(HttpHeaders.AUTHORIZATION, bearer(1, MemberRole.ADMIN)).exchange();
    }

    private MvcTestResult retry(long claimId, long expectedVersion, MemberRole role) {
        return mvc.post().uri("/api/v1/admin/claims/{claimId}/retries", claimId).header(HttpHeaders.AUTHORIZATION, bearer(1, role))
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":" + expectedVersion + "}").exchange();
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders.orders where id = ?", String.class, orderId);
    }

    /**
     * 결과 미확인으로 닫힌 환불 — 고객 취소의 PG 응답이 미확인(UNKNOWN)이었고 재시도 상한을 넘은 상태를 만든다. 상한을 다 채우는 대신 스케줄러가 남기는 값
     * ({@code REFUND_FAILED · REFUND_UNCONFIRMED}, 다음 시각 없음)을 직접 쓴다.
     */
    private void exhaustUnconfirmedRefund(long orderId) {
        jdbc.update("update orders.claim set status = 'REFUND_FAILED', failure_code = 'REFUND_UNCONFIRMED', next_attempt_at = null, version = version + 1 "
            + "where order_id = ? and type = 'CANCELLATION'", orderId);
    }

    private long unconfirmedClaim(Order order) {
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.unknown());
        customerCancel(order.id());
        exhaustUnconfirmedRefund(order.id());
        return claimId(order.id());
    }

    @Test
    @DisplayName("결과 미확인으로 멈춘 취소는 상세에 업무 언어 · 다시 시도 가능으로 보이고, 다시 시도하면 같은 접수로 환불 · 복원까지 끝나 주문이 취소 완료다")
    void unconfirmedRefundCanBeRetriedToCompletion() throws Exception {
        Order order = paidOrder();
        long claimId = unconfirmedClaim(order);

        MvcTestResult before = detail(claimId);
        assertThat(before).hasStatus(200);
        assertThat(before).bodyJson().extractingPath("$.dataBody.recovery.situation.code").isEqualTo("REFUND_UNCONFIRMED");
        assertThat(before).bodyJson().extractingPath("$.dataBody.recovery.retryable").isEqualTo(true);
        assertThat(before).bodyJson().extractingPath("$.dataBody.recovery.nextAutomaticAttemptAt").isNull();
        assertThat(before.getResponse().getContentAsString()).doesNotContain("REFUND_UNCONFIRMED\"}").doesNotContain("attemptCount");

        // payment 는 같은 claimId 의 늦은 성공을 그대로 돌려준다(SUCCEEDED 재요청은 PG 를 부르지 않고 200)
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.succeeded());
        MvcTestResult retried = retry(claimId, version(order.id()), MemberRole.MANAGER);

        assertThat(retried).hasStatus(200);
        assertThat(retried).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("COMPLETED");
        assertThat(retried).bodyJson().extractingPath("$.dataBody.recovery.situation.code").isEqualTo("NO_ACTION");
        assertThat(retried).bodyJson().extractingPath("$.dataBody.recovery.retryable").isEqualTo(false);
        assertThat(orderStatus(order.id())).isEqualTo("CANCELED");
        verify(paymentCancelPort, times(2)).cancel(anyLong(), org.mockito.ArgumentMatchers.eq(claimId), org.mockito.ArgumentMatchers.eq(order.quote().totalAmount()));
    }

    @Test
    @DisplayName("다시 시도해도 결과가 미확인이면 200 이고 자동 처리 중으로 돌아간다 — 응답의 상태 · 처리 상황으로 결과를 읽는다")
    void retryStillUnknownIsAutoProcessing() {
        Order order = paidOrder();
        long claimId = unconfirmedClaim(order);

        MvcTestResult retried = retry(claimId, version(order.id()), MemberRole.ADMIN);

        assertThat(retried).hasStatus(200);
        assertThat(retried).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("REQUESTED");
        assertThat(retried).bodyJson().extractingPath("$.dataBody.recovery.situation.code").isEqualTo("AUTO_PROCESSING");
        assertThat(orderStatus(order.id())).isEqualTo("CANCEL_REQUESTED");
    }

    @Test
    @DisplayName("결제사가 거절한 환불은 다시 시도할 수 없다 — 상세 retryable=false, 요청은 409 ORDER_038 이고 결제를 다시 부르지 않는다")
    void rejectedRefundIsNotRetried() {
        Order order = paidOrder();
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.rejected("PAYMENT_019"));
        customerCancel(order.id());
        long claimId = claimId(order.id());

        assertThat(detail(claimId)).bodyJson().extractingPath("$.dataBody.recovery.retryable").isEqualTo(false);
        MvcTestResult retried = retry(claimId, version(order.id()), MemberRole.ADMIN);

        assertThat(retried).hasStatus(409);
        assertThat(retried).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_038");
        assertThat(claim(order.id())).containsEntry("failure_code", "PAYMENT_019");
        verify(paymentCancelPort, times(1)).cancel(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("재고 복원이 거절돼 멈춘 취소는 다시 시도하면 결제를 다시 부르지 않고 복원만 다시 보낸다")
    void restorationFailureRetriesOnlyRestoration() {
        Order order = paidOrder();
        doThrow(new StockReservationException(StockReservationException.Failure.QUANTITY_EXCEEDED))
            .when(stockRestorationPort).restoreCancellation(anyLong(), anyLong(), any());
        customerCancel(order.id());
        long claimId = claimId(order.id());
        assertThat(claim(order.id())).containsEntry("status", "RESTORATION_FAILED");
        assertThat(detail(claimId)).bodyJson().extractingPath("$.dataBody.recovery.situation.code").isEqualTo("RESTORATION_REJECTED");

        doAnswer(invocation -> null).when(stockRestorationPort).restoreCancellation(anyLong(), anyLong(), any());
        assertThat(retry(claimId, version(order.id()), MemberRole.ADMIN)).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("COMPLETED");

        verify(paymentCancelPort, times(1)).cancel(anyLong(), anyLong(), any());
        verify(stockRestorationPort, times(2)).restoreCancellation(org.mockito.ArgumentMatchers.eq(claimId), anyLong(), any());
    }

    @Test
    @DisplayName("본 화면보다 낡은 버전이면 409 ORDER_037 이고 원격을 부르지 않는다")
    void staleVersionIsConflict() {
        Order order = paidOrder();
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.rejected("PAYMENT_019"));
        customerCancel(order.id());
        long claimId = claimId(order.id());

        MvcTestResult result = retry(claimId, version(order.id()) - 1, MemberRole.ADMIN);

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_037");
        verify(paymentCancelPort, times(1)).cancel(anyLong(), anyLong(), any());
        assertThat(claim(order.id())).containsEntry("status", "REFUND_FAILED");
    }

    @Test
    @DisplayName("처리 완료 접수는 409 ORDER_038, 자동 처리 lease 안의 접수는 409 ORDER_039 이고 원격을 부르지 않는다")
    void completedAndActiveLeaseCannotBeRetried() {
        Order completed = paidOrder();
        customerCancel(completed.id());
        MvcTestResult done = retry(claimId(completed.id()), version(completed.id()), MemberRole.ADMIN);
        assertThat(done).hasStatus(409);
        assertThat(done).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_038");

        Order inFlight = paidOrder();
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.unknown());
        customerCancel(inFlight.id());
        assertThat(claim(inFlight.id())).containsEntry("status", "REQUESTED");
        MvcTestResult leased = retry(claimId(inFlight.id()), version(inFlight.id()), MemberRole.ADMIN);
        assertThat(leased).hasStatus(409);
        assertThat(leased).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_039");
        assertThat(detail(claimId(inFlight.id()))).bodyJson().extractingPath("$.dataBody.recovery.situation.code").isEqualTo("AUTO_PROCESSING");
        verify(paymentCancelPort, times(2)).cancel(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("STAFF 는 실제 보안 체인에서도 다시 시도 403 이고 클레임이 바뀌지 않는다")
    void staffMayNotRetry() {
        Order order = paidOrder();
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.rejected("PAYMENT_019"));
        customerCancel(order.id());
        long before = version(order.id());

        assertThat(retry(claimId(order.id()), before, MemberRole.STAFF)).hasStatus(403);

        assertThat(version(order.id())).isEqualTo(before);
    }

    /**
     * {@code racer} 스레드의 첫 클레임 저장만 버전을 읽은 뒤 · 저장 직전에 멈춘다. 그동안 다른 쪽이 같은 버전으로 끝까지 커밋한 뒤 풀어 주면, 멈춘 쪽은
     * 낡은 버전으로 저장해 CAS 에서 져야 한다 — 두 요청이 순서대로 실행돼 우연히 통과하는 것이 아니라 경합 창을 직접 만든다.
     */
    private Thread pauseFirstUpdateOf(Runnable racer, CountDownLatch reached, CountDownLatch release) {
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("racer") && reached.getCount() > 0) {
                reached.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("racer was not released");
                }
            }
            return invocation.callRealMethod();
            // 주입된 빈은 트랜잭션 프록시라 stub 은 그 안의 spy 에 건다(프록시로 부르면 MANDATORY 트랜잭션 검사에 걸린다)
        }).when(org.springframework.test.util.AopTestUtils.<ClaimRepositoryPort>getUltimateTargetObject(claimRepository)).update(any());
        Thread thread = new Thread(racer, "racer");
        thread.start();
        return thread;
    }

    @Test
    @DisplayName("같은 화면 버전의 두 운영자 요청 — 저장 직전에 멈춘 쪽은 다른 쪽이 먼저 커밋한 뒤 CAS 에서 져 409 ORDER_037 이고 결제 취소는 한 번만 다시 나간다")
    void concurrentRetriesLoseOnCasAndSendRefundOnce() throws Exception {
        Order order = paidOrder();
        long claimId = unconfirmedClaim(order);
        long seen = version(order.id());
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.succeeded());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        int[] racerStatus = new int[1];
        String[] racerCode = new String[1];

        Thread racer = pauseFirstUpdateOf(() -> {
            MvcTestResult result = retry(claimId, seen, MemberRole.ADMIN);
            racerStatus[0] = result.getResponse().getStatus();
            try {
                racerCode[0] = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.dataHeader.resultCode");
            } catch (Exception exception) {
                racerCode[0] = exception.getClass().getSimpleName();
            }
        }, reached, release);
        assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();

        MvcTestResult winner = retry(claimId, seen, MemberRole.MANAGER);
        release.countDown();
        racer.join(15_000);

        assertThat(winner).hasStatus(200);
        assertThat(winner).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("COMPLETED");
        assertThat(racerStatus[0]).isEqualTo(409);
        assertThat(racerCode[0]).isEqualTo("ORDER_037");
        verify(paymentCancelPort, times(2)).cancel(anyLong(), org.mockito.ArgumentMatchers.eq(claimId), any());
        assertThat(claim(order.id())).containsEntry("status", "COMPLETED");
    }

    @Test
    @DisplayName("재시도 스케줄러가 선점 저장 직전에 멈춘 사이 운영자가 다시 시도해 커밋하면 스케줄러는 CAS 에서 져 건너뛴다 — 결제 취소는 한 번만 다시 나간다")
    void schedulerLosesToOperatorOnCas() throws Exception {
        Order order = paidOrder();
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.unknown());
        customerCancel(order.id());
        // 백오프가 끝나 스케줄러 · 운영자 둘 다 집을 수 있는 환불 대기
        jdbc.update("update orders.claim set next_attempt_at = ?, version = version + 1 where order_id = ? and type = 'CANCELLATION'",
            java.sql.Timestamp.from(Instant.now().minusSeconds(60)), order.id());
        long claimId = claimId(order.id());
        long seen = version(order.id());
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.succeeded());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread scheduler = pauseFirstUpdateOf(() -> retryScheduler.retryDueCancellations(), reached, release);
        assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(retry(claimId, seen, MemberRole.ADMIN)).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("COMPLETED");
        release.countDown();
        scheduler.join(15_000);

        verify(paymentCancelPort, times(2)).cancel(anyLong(), org.mockito.ArgumentMatchers.eq(claimId), any());
        assertThat(claim(order.id())).containsEntry("status", "COMPLETED");
    }

    @Test
    @DisplayName("없는 클레임은 404 ORDER_032 다")
    void unknownClaimIsNotFound() {
        MvcTestResult result = retry(ids.generateId(), 0, MemberRole.ADMIN);

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_032");
        verifyNoInteractions(paymentCancelPort);
    }
}
