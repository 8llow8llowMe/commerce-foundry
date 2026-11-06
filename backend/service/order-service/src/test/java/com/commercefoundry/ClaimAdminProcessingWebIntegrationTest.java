package com.commercefoundry;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 후 취소 수습 (#17 PR-A) — 고객 취소로 종결 실패를 만든 뒤 어드민 재시도 · 거절 · 수동 환불 확정 · 복원 생략 종결을 실제 JWT · 권한 · Facade · 단계별
 * 트랜잭션 · H2 로 관통한다. payment · product 호출만 스텁한다. 컨텍스트는 {@code OrderCancellationWebIntegrationTest} 와 같은 설정이라 같이 쓴다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {"order.cancellation.retry.enabled=true", "order.cancellation.retry.interval=1h"})
class ClaimAdminProcessingWebIntegrationTest {

    private static final long ADMIN = 4611686018427387000L;
    private static final String REASON = "{\"reason\":\"PG 확인 완료\"}";

    @Autowired MockMvcTester mvc;
    @Autowired OrderRepositoryPort orders;
    @Autowired OrderPaymentRepositoryPort payments;
    @Autowired SnowflakeIdGenerator ids;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean PaymentCancelPort paymentCancelPort;
    @MockitoBean StockRestorationPort stockRestorationPort;

    @BeforeEach
    void rejectRefundByDefault() {
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.rejected("PAYMENT_016"));
    }

    /** 회원 주문을 넣고 상태를 바꾼 뒤 결제 완료 기록(승인 금액 = 총액)을 넣는다. */
    Order create(OrderStatus status) {
        Order order = Order.place(ids.generateId(), TestTokens.MEMBER_ID, domesticQuote(quoteLine(101, 10_000, 2), quoteLine(102, 5_000, 1)),
            ids::generateId, address("KR"), ids.generateId(), Instant.now().plusSeconds(600));
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            orders.insert(order, OrderStatusHistory.created(ids.generateId(), order));
            jdbc.update("update orders.orders set status = ?, version = version + 1 where id = ?", status.name(), order.id());
            Order paid = new Order(order.id(), order.memberId(), OrderStatus.PAID, order.quote(), order.itemIds(), order.shippingAddress(),
                order.stockReservationId(), order.paymentDueAt(), null, null, null, 1L);
            payments.insert(OrderPayment.paid(ids.generateId(), paid, ids.generateId(), order.quote().totalAmount(), Instant.now(), UUID.randomUUID()));
        });
        return order;
    }

    MvcTestResult cancel(long orderId) {
        return mvc.post().uri("/api/v1/orders/{orderId}/cancellation", orderId).header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.MEMBER_ID, MemberRole.CUSTOMER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ORDER_MISTAKE\"}").exchange();
    }

    MvcTestResult admin(long claimId, String action, String body) {
        var request = mvc.post().uri("/api/v1/admin/claims/{claimId}/" + action, claimId).header(HttpHeaders.AUTHORIZATION, bearer(ADMIN, MemberRole.ADMIN));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    static String bearer(long memberId, MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(memberId, role).tokenValue();
    }

    /** 고객 취소 → 환불 종결 거절(PAYMENT_016) — 클레임 REFUND_FAILED · 주문 CANCEL_REQUESTED. */
    long refundFailedClaim(Order order) {
        assertThat(cancel(order.id())).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("CANCEL_REQUESTED");
        assertThat(claim(order.id())).containsEntry("status", "REFUND_FAILED").containsEntry("failure_code", "PAYMENT_016");
        return ((Number) claim(order.id()).get("id")).longValue();
    }

    Map<String, Object> claim(long orderId) {
        return jdbc.queryForMap("select id, status, failure_code, decision_reason, decided_by_member_id, decided_at from orders.claim "
            + "where order_id = ? and type = 'CANCELLATION'", orderId);
    }

    String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders.orders where id = ?", String.class, orderId);
    }

    @Test
    void refundFailedClaimRetriedByAdminCompletesCancellation() {
        Order order = create(OrderStatus.PAID);
        long claimId = refundFailedClaim(order);
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.succeeded());

        MvcTestResult result = admin(claimId, "retries", null);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("COMPLETED");
        assertThat(result).bodyJson().extractingPath("$.dataBody.paymentId").isNotNull();
        assertThat(orderStatus(order.id())).isEqualTo("CANCELED");
        verify(paymentCancelPort, times(2)).cancel(anyLong(), eq(claimId), eq(order.quote().totalAmount()));
        verify(stockRestorationPort).restoreCancellation(claimId, order.stockReservationId(), new TreeMap<>(Map.of(101L, 2, 102L, 1)));
        assertThat(admin(claimId, "retries", null)).as("완료 뒤 재시도는 바꾸지 않은 200").hasStatus(200);
        verify(paymentCancelPort, times(2)).cancel(anyLong(), anyLong(), any());
    }

    @Test
    void rejectionRestoresOrderBlocksCustomerRetryAndHidesCancelButton() {
        Order order = create(OrderStatus.PREPARING);
        long claimId = refundFailedClaim(order);

        MvcTestResult result = admin(claimId, "rejection", REASON);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("REJECTED");
        assertThat(result).bodyJson().extractingPath("$.dataBody.decisionReason").isEqualTo("PG 확인 완료");
        assertThat(result).bodyJson().extractingPath("$.dataBody.decidedById").isEqualTo(String.valueOf(ADMIN));
        assertThat(orderStatus(order.id())).isEqualTo("PREPARING");
        assertThat(claim(order.id())).containsEntry("status", "REJECTED").containsEntry("failure_code", null).containsEntry("decision_reason", "PG 확인 완료");
        assertThat(jdbc.queryForMap("select from_status, to_status, changed_by_type, changed_by_id, reason from orders.order_status_history "
            + "where order_id = ? order by id desc limit 1", order.id())).containsEntry("from_status", "CANCEL_REQUESTED").containsEntry("to_status", "PREPARING")
            .containsEntry("changed_by_type", "ADMIN").containsEntry("reason", "PG 확인 완료");
        assertThat(admin(claimId, "rejection", REASON)).as("다시 거절은 바꾸지 않은 200").hasStatus(200);
        assertThat(admin(claimId, "retries", null)).as("거절된 클레임은 재시도하지 않는다").bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_036");

        assertFailure(cancel(order.id()), 409, "ORDER_037");
        String token = bearer(TestTokens.MEMBER_ID, MemberRole.CUSTOMER);
        var detail = mvc.get().uri("/api/v1/orders/{orderId}", order.id()).header(HttpHeaders.AUTHORIZATION, token).exchange();
        assertThat(detail).bodyJson().extractingPath("$.dataBody.cancelable").isEqualTo(false);
        var list = mvc.get().uri("/api/v1/orders?size=100").header(HttpHeaders.AUTHORIZATION, token).exchange();
        assertThat(list).bodyJson().extractingPath("$.dataBody.contents[?(@.orderId=='" + order.id() + "')].cancelable").asArray().containsExactly(false);
        verify(paymentCancelPort, times(1)).cancel(anyLong(), anyLong(), any());
    }

    @Test
    void unconfirmedRefundCannotBeRejectedButCanBeConfirmedManually() {
        Order order = create(OrderStatus.PAID);
        long claimId = refundFailedClaim(order);
        jdbc.update("update orders.claim set failure_code = 'REFUND_UNCONFIRMED' where id = ?", claimId);

        assertFailure(admin(claimId, "rejection", REASON), 409, "ORDER_038");
        assertThat(orderStatus(order.id())).isEqualTo("CANCEL_REQUESTED");

        MvcTestResult confirmed = admin(claimId, "refund-confirmation", REASON);

        assertThat(confirmed).hasStatus(200);
        assertThat(confirmed).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("COMPLETED");
        assertThat(orderStatus(order.id())).isEqualTo("CANCELED");
        verify(stockRestorationPort).restoreCancellation(claimId, order.stockReservationId(), new TreeMap<>(Map.of(101L, 2, 102L, 1)));
        assertThat(jdbc.queryForList("select changed_by_type from orders.order_status_history where order_id = ? and to_status = 'CANCELED'", String.class,
            order.id())).containsExactly("ADMIN");
        assertThat(admin(claimId, "refund-confirmation", REASON)).as("확정 뒤 다시 확정은 바꾸지 않은 200").hasStatus(200);
        verify(paymentCancelPort, times(1)).cancel(anyLong(), anyLong(), any());
    }

    @Test
    void restorationFailedClaimIsCompletedByAdminWithoutRestoring() {
        when(paymentCancelPort.cancel(anyLong(), anyLong(), any())).thenReturn(PaymentCancelQueryResult.succeeded());
        doThrow(new StockReservationException(StockReservationException.Failure.QUANTITY_EXCEEDED))
            .when(stockRestorationPort).restoreCancellation(anyLong(), anyLong(), any());
        Order order = create(OrderStatus.PAID);
        assertThat(cancel(order.id())).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("CANCELED");
        long claimId = ((Number) claim(order.id()).get("id")).longValue();
        assertThat(claim(order.id())).containsEntry("status", "RESTORATION_FAILED");
        assertFailure(admin(claimId, "rejection", REASON), 409, "ORDER_036");

        MvcTestResult result = admin(claimId, "completion", REASON);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("COMPLETED");
        assertThat(claim(order.id())).containsEntry("status", "COMPLETED").containsEntry("failure_code", null).containsEntry("decision_reason", "PG 확인 완료");
        assertThat(orderStatus(order.id())).isEqualTo("CANCELED");
        verify(stockRestorationPort, times(1)).restoreCancellation(anyLong(), anyLong(), any());
    }

    @Test
    void missingClaimIs404AndStaffIsForbidden() {
        assertFailure(admin(ids.generateId(), "completion", REASON), 404, "ORDER_032");
        MvcTestResult staff = mvc.post().uri("/api/v1/admin/claims/{claimId}/retries", 1L).header(HttpHeaders.AUTHORIZATION, bearer(ADMIN, MemberRole.STAFF))
            .exchange();
        assertThat(staff).hasStatus(403);
    }

    private static void assertFailure(MvcTestResult result, int status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
    }
}
