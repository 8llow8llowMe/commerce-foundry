package com.commercefoundry.domainlayer.order.support;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimCancellationRejectionProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * H2 · PostgreSQL 에서 같게 확인하는 결제 후 취소 거절 저장 계약 (#17 A4) — 실제 Processor · 트랜잭션 · DB 다. payment 무환불 판정은 부르지 않는다(Facade 가
 * 트랜잭션 밖에서 부르고, 여기서는 확정을 받은 뒤의 DB 단계만 본다). 취소 + 거절 + 결정의 직전 실패 코드 행이 claim check 제약을 통과하는지가 핵심이다 — DDL 은 없다.
 *
 * <p>주문 항목은 SKU 101 × 2(10,000) · SKU 102 × 1(5,000), 배송비 3,000 — 결제 총액 28,000. 테스트 값은 모두 가짜다.
 */
public abstract class ClaimCancellationRejectionContract {
    private static final AtomicLong IDS = new AtomicLong(990_000);
    protected static final long MEMBER = 7L;
    protected static final long ADMIN = 9L;
    protected static final String REASON = "결제사 화면에서 환불 없음 확인, 고객과 통화해 출고 진행";
    @Autowired protected OrderRepositoryPort orders;
    @Autowired protected ClaimRepositoryPort claims;
    @Autowired protected OrderPaymentRepositoryPort payments;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected OrderCancellationProcessor cancellation;
    @Autowired protected ClaimCancellationRejectionProcessor rejection;

    protected TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** 결제 완료(결제 완료 기록 포함) 주문을 {@code before} 상태로 두고 고객이 결제 후 취소를 접수한 뒤, 결제사가 환불을 종결 거절한 상태. */
    protected Claim rejectedRefund(OrderStatus before) {
        long id = IDS.incrementAndGet();
        Order order = Order.place(id, MEMBER, domesticQuote(quoteLine(101, 10_000, 2), quoteLine(102, 5_000, 1)), IDS::incrementAndGet, address("KR"),
            IDS.incrementAndGet(), Instant.now().plusSeconds(600));
        tx().executeWithoutResult(s -> {
            orders.insert(order, OrderStatusHistory.created(IDS.incrementAndGet(), order));
            Order paid = new Order(order.id(), order.memberId(), OrderStatus.PAID, order.quote(), order.itemIds(), order.shippingAddress(),
                order.stockReservationId(), order.paymentDueAt(), null, null, null, 1L);
            payments.insert(OrderPayment.paid(IDS.incrementAndGet(), paid, IDS.incrementAndGet(), order.quote().totalAmount(), Instant.now(),
                UUID.randomUUID()));
            jdbc.update("update orders.orders set status = ?, version = version + 1 where id = ?", before.name(), id);
        });
        Claim requested = cancellation.request(MEMBER, id, OrderCancelReason.ORDER_MISTAKE).claim();
        return tx().execute(s -> claims.update(requested.failRefund("PAYMENT_019")));
    }

    protected Order read(long orderId) {
        return tx().execute(s -> orders.findById(orderId).orElseThrow());
    }

    protected Claim readClaim(long claimId) {
        return claims.findById(claimId).orElseThrow();
    }

    protected Map<String, Object> claimRow(long claimId) {
        return jdbc.queryForMap("select status, failure_code, next_attempt_at, payment_id, expected_refund_amount, decision_reason, decided_by_member_id, "
            + "decided_at, decision_failure_code, version from orders.claim where id = ?", claimId);
    }

    protected List<Map<String, Object>> adminHistories(long orderId) {
        return jdbc.queryForList("select from_status, to_status, changed_by_id, reason from orders.order_status_history where order_id = ? "
            + "and changed_by_type = 'ADMIN'", orderId);
    }

    protected static void assertCode(Runnable call, OrderErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(OrderException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(code));
    }

    @Test
    void rejectionStoresDecisionWithPreviousFailureCodeAndRestoresOrder() {
        Claim failed = rejectedRefund(OrderStatus.PREPARING);
        Map<String, Object> before = claimRow(failed.id());

        assertThat(rejection.prepare(failed.id(), failed.version())).isEqualTo(readClaim(failed.id()));
        assertThat(claimRow(failed.id())).as("자격 확인은 아무것도 쓰지 않는다").isEqualTo(before);

        Claim rejected = rejection.reject(failed.id(), failed.version(), REASON, ADMIN);

        // 취소 + 거절 + 결정의 직전 실패 코드 — ck_claim_admin_decision · ck_claim_cancellation_next_attempt · ck_claim_cancellation_failure_code 를 통과한다
        assertThat(readClaim(failed.id())).isEqualTo(rejected);
        assertThat(rejected.isRejectedCancellation()).isTrue();
        Map<String, Object> row = claimRow(failed.id());
        assertThat(row).containsEntry("status", "REJECTED").containsEntry("failure_code", null).containsEntry("next_attempt_at", null)
            .containsEntry("decision_reason", REASON).containsEntry("decision_failure_code", "PAYMENT_019");
        assertThat(((Number) row.get("decided_by_member_id")).longValue()).isEqualTo(ADMIN);
        assertThat(row.get("decided_at")).isNotNull();
        assertThat(row.get("payment_id")).as("결제 아이디 · 환불액은 그대로").isEqualTo(before.get("payment_id"));
        assertThat(row.get("expected_refund_amount")).isEqualTo(before.get("expected_refund_amount"));
        assertThat(((Number) row.get("version")).longValue()).isGreaterThan(failed.version());
        assertThat(read(failed.orderId()).status()).isEqualTo(OrderStatus.PREPARING);
        assertThat(adminHistories(failed.orderId())).singleElement().satisfies(history -> {
            assertThat(history.get("from_status")).isEqualTo("CANCEL_REQUESTED");
            assertThat(history.get("to_status")).isEqualTo("PREPARING");
            assertThat(((Number) history.get("changed_by_id")).longValue()).isEqualTo(ADMIN);
            assertThat(history.get("reason")).isEqualTo(REASON);
        });
        assertThat(claims.sumCanceledQuantities(failed.orderId())).as("거절된 취소는 취소 수량에서 빠진다").isEmpty();
    }

    @Test
    void staleOrRepeatedRejectionChangesNothing() {
        Claim failed = rejectedRefund(OrderStatus.PAID);

        assertCode(() -> rejection.reject(failed.id(), failed.version() + 1, REASON, ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        assertThat(readClaim(failed.id())).isEqualTo(failed);
        assertThat(read(failed.orderId()).status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);

        Claim rejected = rejection.reject(failed.id(), failed.version(), REASON, ADMIN);
        // 같은 화면으로 다시 보낸 거절은 버전이 올라 037, 새 버전이어도 거절된 접수라 041 — 거절은 한 번뿐이다
        assertCode(() -> rejection.reject(failed.id(), failed.version(), REASON, ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        assertCode(() -> rejection.prepare(failed.id(), rejected.version()), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        assertThat(readClaim(failed.id())).isEqualTo(rejected);
        assertThat(read(failed.orderId()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(adminHistories(failed.orderId())).hasSize(1);
    }

    @Test
    void customerRecancelAfterRejectionIsRefusedWithoutNewClaim() {
        Claim failed = rejectedRefund(OrderStatus.PAID);
        rejection.reject(failed.id(), failed.version(), REASON, ADMIN);

        assertCode(() -> cancellation.request(MEMBER, failed.orderId(), OrderCancelReason.CHANGE_OF_MIND), OrderErrorCode.CANCELLATION_ALREADY_REJECTED);

        assertThat(jdbc.queryForObject("select count(*) from orders.claim where order_id = ?", Long.class, failed.orderId())).isEqualTo(1L);
        assertThat(read(failed.orderId()).status()).isEqualTo(OrderStatus.PAID);
    }
}
