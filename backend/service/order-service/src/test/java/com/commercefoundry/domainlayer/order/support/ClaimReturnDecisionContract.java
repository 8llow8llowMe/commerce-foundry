package com.commercefoundry.domainlayer.order.support;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.application.command.ClaimReturnApprovalCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo.FaultRefund;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimReturnDecisionProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy.Fault;
import java.sql.Timestamp;
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
 * H2 · PostgreSQL 에서 같게 확인하는 반품 승인 · 거절 계약 (#17 B2c-2) — 실제 Processor · 트랜잭션 · DB 다. payment · product 는 부르지 않는다(승인의 환불은
 * 커밋 뒤 Facade 가 잇는다).
 *
 * <p>주문 항목은 SKU 101 × 2(10,000) · SKU 102 × 1(5,000), 배송비 3,000 — 결제 총액 28,000. 테스트 값은 모두 가짜다.
 */
public abstract class ClaimReturnDecisionContract {
    private static final AtomicLong IDS = new AtomicLong(980_000);
    protected static final long ADMIN = 9L;
    protected static final String REASON = "검수 결과 사용 흔적이 있어 반품 불가";
    @Autowired protected OrderRepositoryPort orders;
    @Autowired protected ClaimRepositoryPort claims;
    @Autowired protected OrderPaymentRepositoryPort payments;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected ClaimReturnDecisionProcessor decisions;

    protected TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** 배송 완료 뒤 반품 요청 중인 주문 — {@code paid} 면 결제 완료 기록(order_payment)도 둔다. */
    protected Order returnRequestedOrder(boolean paid) {
        long id = IDS.incrementAndGet();
        Order order = Order.place(id, 7, domesticQuote(quoteLine(101, 10_000, 2), quoteLine(102, 5_000, 1)), IDS::incrementAndGet, address("KR"),
            IDS.incrementAndGet(), Instant.now().plusSeconds(600));
        tx().executeWithoutResult(s -> {
            orders.insert(order, OrderStatusHistory.created(IDS.incrementAndGet(), order));
            if (paid) {
                Order paidOrder = new Order(order.id(), order.memberId(), OrderStatus.PAID, order.quote(), order.itemIds(), order.shippingAddress(),
                    order.stockReservationId(), order.paymentDueAt(), null, null, null, 1L);
                payments.insert(OrderPayment.paid(IDS.incrementAndGet(), paidOrder, IDS.incrementAndGet(), order.quote().totalAmount(), Instant.now(),
                    UUID.randomUUID()));
            }
            jdbc.update("update orders.orders set status = 'RETURN_REQUESTED', delivered_at = ?, version = version + 1 where id = ?",
                Timestamp.from(Instant.now().minusSeconds(3_600)), id);
        });
        return read(order.id());
    }

    protected Order read(long orderId) {
        return tx().execute(s -> orders.findById(orderId).orElseThrow());
    }

    /** 고객 반품 접수(REQUESTED) — {@code index} 번째 주문 항목 1개. */
    protected Claim requestReturn(Order order, int index, ClaimItem.Reason reason) {
        Claim requested = new Claim(IDS.incrementAndGet(), order.id(), UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(IDS.incrementAndGet(), order.itemIds().get(index), 1, reason)), null, null);
        return tx().execute(s -> claims.insert(requested));
    }

    protected Claim readClaim(long claimId) {
        return claims.findById(claimId).orElseThrow();
    }

    protected Map<String, Object> claimRow(long claimId) {
        return jdbc.queryForMap("select status, expected_refund_amount, expected_refund_currency, payment_id, return_fault, restock, deduction_amount, "
            + "approved_by_member_id, approved_at, next_attempt_at, decision_reason, decided_by_member_id, decided_at, decision_failure_code, version "
            + "from orders.claim where id = ?", claimId);
    }

    protected List<Map<String, Object>> adminHistories(Order order) {
        return jdbc.queryForList("select from_status, to_status, changed_by_type, changed_by_id, reason from orders.order_status_history"
            + " where order_id = ? and changed_by_type = 'ADMIN'", order.id());
    }

    /** 상세 미리보기가 낸 귀책의 정책 금액 — 승인 본문의 예상 환불액은 이것 − 차감액이다. */
    protected long previewPolicyAmount(Claim claim, Fault fault) {
        return tx().execute(s -> decisions.preview(claim)).refundByFault().stream().filter(refund -> refund.fault() == fault)
            .mapToLong(FaultRefund::policyAmount).findFirst().orElseThrow();
    }

    protected Claim approve(Claim claim, Fault fault, boolean restock, long deduction) {
        long policy = previewPolicyAmount(claim, fault);
        return decisions.approve(new ClaimReturnApprovalCommand(claim.id(), claim.version(), fault, restock, krw(deduction), krw(policy - deduction)),
            ADMIN);
    }

    protected static void assertCode(Runnable call, OrderErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(OrderException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(code));
    }

    @Test
    void approvalStoresDecisionColumnsAndKeepsOrderReturnRequested() {
        Order order = returnRequestedOrder(true);
        Claim requested = requestReturn(order, 0, ClaimItem.Reason.CHANGE_OF_MIND);
        long policy = previewPolicyAmount(requested, Fault.SELLER);

        Claim approved = approve(requested, Fault.SELLER, false, 2_000);

        assertThat(readClaim(requested.id())).isEqualTo(approved);
        Map<String, Object> row = claimRow(requested.id());
        assertThat(row).containsEntry("status", "APPROVED").containsEntry("expected_refund_currency", "KRW").containsEntry("return_fault", "SELLER")
            .containsEntry("restock", false).containsEntry("decision_reason", null);
        assertThat(((Number) row.get("expected_refund_amount")).longValue()).isEqualTo(policy - 2_000);
        assertThat(((Number) row.get("deduction_amount")).longValue()).isEqualTo(2_000);
        assertThat(((Number) row.get("approved_by_member_id")).longValue()).isEqualTo(ADMIN);
        assertThat(row.get("payment_id")).isNotNull();
        assertThat(row.get("approved_at")).isNotNull();
        assertThat(row.get("next_attempt_at")).as("승인한 요청이 환불을 끝낼 lease").isNotNull();
        assertThat(((Number) row.get("version")).longValue()).isGreaterThan(requested.version());
        assertThat(read(order.id()).status()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(adminHistories(order)).isEmpty();
    }

    @Test
    void staleOrAlreadyDecidedApprovalChangesNothing() {
        Order order = returnRequestedOrder(true);
        Claim requested = requestReturn(order, 0, ClaimItem.Reason.CHANGE_OF_MIND);
        long policy = previewPolicyAmount(requested, Fault.CUSTOMER);
        ClaimReturnApprovalCommand seen = new ClaimReturnApprovalCommand(requested.id(), requested.version(), Fault.CUSTOMER, true, krw(0), krw(policy));

        assertCode(() -> decisions.approve(new ClaimReturnApprovalCommand(requested.id(), requested.version() + 1, Fault.CUSTOMER, true, krw(0),
            krw(policy)), ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        assertThat(readClaim(requested.id())).isEqualTo(requested);

        Claim approved = decisions.approve(seen, ADMIN);
        // 같은 화면으로 다시 보낸 승인(더블클릭)은 버전이 올라 037, 새 버전이어도 결정 대기가 아니라 042 — 승인은 한 번뿐이다
        assertCode(() -> decisions.approve(seen, ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        assertCode(() -> decisions.approve(new ClaimReturnApprovalCommand(requested.id(), approved.version(), Fault.CUSTOMER, true, krw(0), krw(policy)),
            ADMIN), OrderErrorCode.RETURN_NOT_DECIDABLE);
        assertCode(() -> decisions.reject(requested.id(), approved.version(), REASON, ADMIN), OrderErrorCode.RETURN_NOT_DECIDABLE);
        assertThat(readClaim(requested.id())).isEqualTo(approved);
    }

    @Test
    void inFlightReturnBlocksApprovalButNotRejection() {
        Order order = returnRequestedOrder(true);
        Claim first = requestReturn(order, 0, ClaimItem.Reason.DEFECTIVE);
        Claim second = requestReturn(order, 1, ClaimItem.Reason.CHANGE_OF_MIND);
        approve(first, Fault.SELLER, true, 0);

        assertCode(() -> decisions.approve(new ClaimReturnApprovalCommand(second.id(), second.version(), Fault.CUSTOMER, true, krw(0), krw(5_000)),
            ADMIN), OrderErrorCode.RETURN_REFUND_IN_FLIGHT);
        assertThat(readClaim(second.id())).isEqualTo(second);

        Claim rejected = decisions.reject(second.id(), second.version(), REASON, ADMIN);

        assertThat(rejected.status()).isEqualTo(Claim.Status.REJECTED);
        assertThat(read(order.id()).status()).as("승인된 첫 반품이 열려 있어 반품 요청에 머문다").isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(adminHistories(order)).isEmpty();
    }

    @Test
    void rejectionStoresDecisionAndSettlesOrderWithAdminHistory() {
        Order order = returnRequestedOrder(true);
        Claim requested = requestReturn(order, 0, ClaimItem.Reason.CHANGE_OF_MIND);

        Claim rejected = decisions.reject(requested.id(), requested.version(), REASON, ADMIN);

        assertThat(readClaim(requested.id())).isEqualTo(rejected);
        Map<String, Object> row = claimRow(requested.id());
        assertThat(row).containsEntry("status", "REJECTED").containsEntry("decision_reason", REASON).containsEntry("decision_failure_code", null)
            .containsEntry("payment_id", null).containsEntry("approved_at", null).containsEntry("return_fault", null).containsEntry("next_attempt_at", null);
        assertThat(((Number) row.get("decided_by_member_id")).longValue()).isEqualTo(ADMIN);
        assertThat(row.get("decided_at")).isNotNull();
        Order settled = read(order.id());
        assertThat(settled.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(settled.deliveredAt()).as("배송 완료 시각은 그대로").isEqualTo(order.deliveredAt());
        assertThat(adminHistories(order)).singleElement().satisfies(history -> {
            assertThat(history.get("from_status")).isEqualTo("RETURN_REQUESTED");
            assertThat(history.get("to_status")).isEqualTo("DELIVERED");
            assertThat(((Number) history.get("changed_by_id")).longValue()).isEqualTo(ADMIN);
            assertThat(history.get("reason")).isEqualTo(REASON);
        });
    }

    @Test
    void missingPaymentRecordIsNotCalculableButRejectable() {
        Order order = returnRequestedOrder(false);
        Claim requested = requestReturn(order, 0, ClaimItem.Reason.CHANGE_OF_MIND);

        assertCode(() -> decisions.approve(new ClaimReturnApprovalCommand(requested.id(), requested.version(), null, true, krw(0), krw(10_000)), ADMIN),
            OrderErrorCode.RETURN_REFUND_NOT_CALCULABLE);
        assertThat(readClaim(requested.id())).isEqualTo(requested);

        assertThat(decisions.reject(requested.id(), requested.version(), REASON, ADMIN).status()).isEqualTo(Claim.Status.REJECTED);
    }
}
