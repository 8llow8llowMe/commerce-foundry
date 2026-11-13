package com.commercefoundry.domainlayer.order.support;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.ClaimRepositoryAdapter;
import com.commercefoundry.domainlayer.order.application.command.OrderReturnCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.service.OrderReturnWebFacade;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderHistoryProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy;
import com.commercefoundry.shared.commerce.CurrencyCode;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** H2·PostgreSQL에서 동일하게 확인하는 실제 반품 저장·원자성 계약. */
public abstract class OrderReturnPersistenceContract {
    private static final AtomicLong IDS = new AtomicLong(930000);
    protected static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    @Autowired protected OrderRepositoryPort orders;
    @Autowired protected OrderReturnWebFacade facade;
    @Autowired protected OrderHistoryProcessor historyProcessor;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected EntityManager entityManager;
    @MockitoSpyBean protected ClaimRepositoryAdapter claimAdapter;

    protected TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    protected Order create(OrderStatus status, int quantity) {
        long id = IDS.incrementAndGet();
        Order order = Order.place(id, 7, domesticQuote(quoteLine(101, 1000, quantity), quoteLine(102, 2000, 2)), IDS::incrementAndGet,
            address("KR"), IDS.incrementAndGet(), NOW);
        tx().executeWithoutResult(s -> {
            orders.insert(order, OrderStatusHistory.created(IDS.incrementAndGet(), order));
            jdbc.update("update orders.orders set status = ?, delivered_at = ?, version = version + 1 where id = ?", status.name(), java.sql.Timestamp.from(NOW), id);
        });
        return read(id);
    }

    protected Order read(long id) {
        return tx().execute(s -> orders.findById(id).orElseThrow());
    }

    protected OrderReturnCommand command(Order order, int quantity) {
        return new OrderReturnCommand(List.of(new OrderReturnCommand.Item(order.itemIds().getFirst(), quantity, ClaimItem.Reason.DEFECTIVE)));
    }

    protected long count(String table, long orderId) {
        return jdbc.queryForObject("select count(*) from orders." + table + " where order_id = ?", Long.class, orderId);
    }

    protected void error(Runnable call, OrderErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(OrderException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    void initialAdditionalAndExhaustedQuantityKeepOneTransition() {
        Order order = create(OrderStatus.DELIVERED, 3);
        var first = facade.request(7, order.id(), UUID.randomUUID(), command(order, 1));
        assertThat(first.created()).isTrue();
        assertThat(first.claim().expectedRefund()).isNull();
        assertThat(first.claim().requestedAt()).isNotNull();
        assertThat(first.claim().status().code()).isEqualTo("REQUESTED");
        assertThat(read(order.id()).status()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        var second = facade.request(7, order.id(), UUID.randomUUID(), command(order, 2));
        assertThat(second.created()).isTrue();
        assertThat(count("claim", order.id())).isEqualTo(2);
        assertThat(count("order_status_history", order.id())).isEqualTo(2);
        error(() -> facade.request(7, order.id(), UUID.randomUUID(), command(order, 1)), OrderErrorCode.RETURN_QUANTITY_EXCEEDED);
        assertThat(orders.findDueForConfirmation(NOW, 1000)).extracting(row -> row.orderId()).doesNotContain(order.id());
    }

    @Test
    void replaySortsBodyAndKeepsDatabaseTimestampEvenAfterStateChanges() {
        Order order = create(OrderStatus.DELIVERED, 2);
        UUID key = UUID.randomUUID();
        var a = new OrderReturnCommand.Item(order.itemIds().getFirst(), 1, ClaimItem.Reason.DEFECTIVE);
        var b = new OrderReturnCommand.Item(order.itemIds().getLast(), 1, ClaimItem.Reason.WRONG_ITEM);
        var first = facade.request(7, order.id(), key, new OrderReturnCommand(List.of(a, b)));
        tx().executeWithoutResult(s -> jdbc.update("update orders.orders set status = 'RETURNED', version = version + 1 where id = ?", order.id()));
        var replay = facade.request(7, order.id(), UUID.fromString(key.toString().toUpperCase()), new OrderReturnCommand(List.of(b, a)));
        assertThat(replay.created()).isFalse();
        assertThat(replay.claim()).isEqualTo(first.claim());
        error(() -> facade.request(7, order.id(), key, command(order, 2)), OrderErrorCode.RETURN_KEY_REUSED);
        error(() -> facade.request(8, order.id(), key, command(order, 1)), OrderErrorCode.NOT_FOUND_ORDER);
        assertThat(count("claim", order.id())).isEqualTo(1);
    }

    @Test
    void foreignDuplicateAndEveryDisallowedStateAreRejected() {
        Order order = create(OrderStatus.DELIVERED, 2);
        error(() -> facade.request(7, order.id(), UUID.randomUUID(), new OrderReturnCommand(List.of(
            new OrderReturnCommand.Item(Long.MAX_VALUE, 1, ClaimItem.Reason.WRONG_ITEM)))), OrderErrorCode.RETURN_ITEM_INVALID);
        var item = command(order, 1).items().getFirst();
        error(() -> facade.request(7, order.id(), UUID.randomUUID(), new OrderReturnCommand(List.of(item, item))), OrderErrorCode.RETURN_ITEM_INVALID);
        for (OrderStatus state : OrderStatus.values()) {
            if (state == OrderStatus.DELIVERED || state == OrderStatus.RETURN_REQUESTED) {
                continue;
            }
            Order other = create(state, 2);
            error(() -> facade.request(7, other.id(), UUID.randomUUID(), command(other, 1)), OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID);
        }
    }

    @Test
    void callerRollbackRestoresOrderHistoryAndClaim() {
        Order order = create(OrderStatus.DELIVERED, 2);
        tx().executeWithoutResult(s -> {
            facade.request(7, order.id(), UUID.randomUUID(), command(order, 1));
            s.setRollbackOnly();
        });
        assertRollback(order);
    }

    @Test
    void actualItemConstraintFailureRollsBackAlreadyWrittenOrderAndHistory() {
        Order order = create(OrderStatus.DELIVERED, 2);
        ClaimRepositoryAdapter target = AopTestUtils.getUltimateTargetObject(claimAdapter);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            entityManager.flush();
            jdbc.update("update orders.claim_item set quantity = 0 where claim_id in (select id from orders.claim where order_id = ?)", order.id());
            return result;
        }).when(target).insert(any());
        try {
            error(() -> facade.request(7, order.id(), UUID.randomUUID(), command(order, 1)), OrderErrorCode.ORDER_SAVE_FAILED);
        } finally {
            reset(target);
        }
        assertRollback(order);
    }

    @Test
    void actualHeaderConstraintFailureRollsBackAlreadyWrittenOrderAndHistory() {
        Order order = create(OrderStatus.DELIVERED, 2);
        ClaimRepositoryAdapter target = AopTestUtils.getUltimateTargetObject(claimAdapter);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            entityManager.flush();
            jdbc.update("update orders.claim set expected_refund_amount = -1, expected_refund_currency = 'KRW' where order_id = ?", order.id());
            return result;
        }).when(target).insert(any());
        try {
            error(() -> facade.request(7, order.id(), UUID.randomUUID(), command(order, 1)), OrderErrorCode.ORDER_SAVE_FAILED);
        } finally {
            reset(target);
        }
        assertRollback(order);
    }

    @Test
    void reservedReturnQuantityExcludesOnlyRejected() {
        // #17 B1 — 환불 실패 · 복원 실패를 빼면 같은 수량을 다시 접수해 이중 환불이 될 수 있다. 거절만 뺀다
        Order order = create(OrderStatus.RETURN_REQUESTED, 9);
        long first = order.itemIds().getFirst();
        long second = order.itemIds().getLast();
        for (Claim.Status status : Claim.Status.values()) {
            tx().executeWithoutResult(s -> claimAdapter.insert(returnClaim(order, status, first)));
        }
        tx().executeWithoutResult(s -> claimAdapter.insert(returnClaim(order, Claim.Status.REJECTED, second)));

        assertThat(claimAdapter.sumRequestedReturnQuantities(order.id())).containsExactly(entry(first, 6L));
        error(() -> facade.request(7, order.id(), UUID.randomUUID(), command(order, 4)), OrderErrorCode.RETURN_QUANTITY_EXCEEDED);
        assertThat(facade.request(7, order.id(), UUID.randomUUID(), command(order, 3)).created()).isTrue();
        assertThat(facade.request(7, order.id(), UUID.randomUUID(), new OrderReturnCommand(List.of(
            new OrderReturnCommand.Item(second, 2, ClaimItem.Reason.CHANGE_OF_MIND)))).created()).as("거절된 수량은 다시 접수할 수 있다").isTrue();
    }

    @Test
    void approvalAndRejectionRoundTrip() {
        Order order = create(OrderStatus.DELIVERED, 2);
        Claim requested = requestReturn(order);
        Instant approvedAt = Instant.parse("2026-11-03T01:02:03.123456789Z");
        Claim.ReturnApproval approval = new Claim.ReturnApproval(false, krw(100), 9, approvedAt);
        Claim approved = requested.approveReturn(order, 77L, new ReturnRefundPolicy.Refund(1_000, 100, CurrencyCode.KRW), false, 9, approvedAt,
            Duration.ofSeconds(120));

        Claim saved = tx().execute(s -> claimAdapter.update(approved));
        Claim read = claimAdapter.findById(requested.id()).orElseThrow();

        assertThat(read).isEqualTo(saved);
        assertThat(read.version()).isEqualTo(requested.version() + 1);
        assertThat(read.approval()).isEqualTo(approval);
        assertThat(read.approval().approvedAt()).isEqualTo(Instant.parse("2026-11-03T01:02:03.123456Z"));
        assertThat(read.isAwaitingRefund()).isTrue();
        assertThat(jdbc.queryForMap("select restock, deduction_amount, approved_by_member_id, payment_id from orders.claim where id = ?", requested.id()))
            .containsEntry("restock", false).containsEntry("deduction_amount", 100L).containsEntry("approved_by_member_id", 9L).containsEntry("payment_id", 77L);
        Claim other = requestReturn(order);
        tx().executeWithoutResult(s -> claimAdapter.update(other.rejectReturn()));
        Claim rejected = claimAdapter.findById(other.id()).orElseThrow();
        assertThat(rejected.status()).isEqualTo(Claim.Status.REJECTED);
        assertThat(rejected.approval()).isNull();
        assertThat(claimAdapter.sumRequestedReturnQuantities(order.id())).containsExactly(entry(order.itemIds().getFirst(), 1L));
    }

    @Test
    void returnApprovalChecksMatchDomain() {
        Order order = create(OrderStatus.DELIVERED, 2);
        long id = requestReturn(order).id();
        Timestamp at = Timestamp.from(NOW);
        String approve = "update orders.claim set status = 'APPROVED', payment_id = 77, expected_refund_amount = 900, expected_refund_currency = 'KRW', "
            + "next_attempt_at = ?, approved_at = ?, approved_by_member_id = ?, restock = true, deduction_amount = ? where id = ?";

        assertThatThrownBy(() -> jdbc.update("update orders.claim set status = 'APPROVED', payment_id = 77, expected_refund_amount = 900, "
            + "expected_refund_currency = 'KRW', next_attempt_at = ? where id = ?", at, id)).as("승인 칸 없음").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update orders.claim set restock = true where id = ?", id)).as("승인 전 승인 칸")
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update orders.claim set payment_id = 77 where id = ?", id)).as("승인 전 결제")
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(approve, null, at, 9, 0, id)).as("대기 상태인데 다음 시각 없음").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(approve, at, at, 9, -1, id)).as("음수 차감").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(approve, at, at, 0, 0, id)).as("관리자 아이디 0").isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(approve, at, at, 9, 0, id)).isEqualTo(1);
        assertThat(claimAdapter.findById(id).orElseThrow().status()).isEqualTo(Claim.Status.APPROVED);
        assertThatThrownBy(() -> jdbc.update("insert into orders.claim (id, order_id, request_key, type, status, expected_refund_amount, "
            + "expected_refund_currency, payment_id, attempt_count, version, created_at, updated_at) values (?, ?, ?, 'CANCELLATION', 'APPROVED', 900, 'KRW', 77, 0, 0, "
            + "?, ?)", IDS.incrementAndGet(), order.id(), UUID.randomUUID(), at, at)).as("취소는 APPROVED 가 없다")
            .isInstanceOf(DataIntegrityViolationException.class);
        // 반품 거절 결정(직전 실패 코드 없음)은 B2 가 쓴다. B1 도메인은 만들지 않지만 읽을 수 있어야 B2 → B1 롤백 때 매퍼가 깨지지 않는다
        long rejectedId = requestReturn(order).id();
        String reject = "update orders.claim set status = 'REJECTED', decision_reason = '검수 불합격', decided_by_member_id = 9, decided_at = ?, "
            + "decision_failure_code = ? where id = ?";
        assertThatThrownBy(() -> jdbc.update(reject.replace("'REJECTED'", "'REQUESTED'"), at, null, rejectedId)).as("접수 반품에는 결정이 없다")
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(reject, at, null, rejectedId)).isEqualTo(1);
        Claim rejectedWithDecision = claimAdapter.findById(rejectedId).orElseThrow();
        assertThat(rejectedWithDecision.status()).isEqualTo(Claim.Status.REJECTED);
        assertThat(rejectedWithDecision.decision().reason()).isEqualTo("검수 불합격");
        assertThat(rejectedWithDecision.decision().previousFailureCode()).isNull();
        assertThatThrownBy(() -> jdbc.update("update orders.claim set payment_id = 77 where id = ?", rejectedId)).as("거절 반품에는 결제가 없다")
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update orders.claim set status = 'REQUESTED', decision_reason = null, decided_by_member_id = null, decided_at = null where id = ?",
            rejectedId);
        // 취소 클레임의 결정 · 승인 칸 (#17 B1 DB 검토 LOW 6)
        String cancellation = "insert into orders.claim (id, order_id, request_key, type, status, expected_refund_amount, expected_refund_currency, payment_id, "
            + "attempt_count, next_attempt_at, decision_reason, decided_by_member_id, decided_at, decision_failure_code, restock, version, created_at, updated_at) "
            + "values (?, ?, ?, 'CANCELLATION', ?, 900, 'KRW', 77, 0, ?, ?, ?, ?, ?, ?, 0, ?, ?)";
        assertThatThrownBy(() -> jdbc.update(cancellation, IDS.incrementAndGet(), order.id(), UUID.randomUUID(), "REQUESTED", at, "확인", 9, at, null, null, at,
            at)).as("실패 코드 없는 결정은 거절에만").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(cancellation, IDS.incrementAndGet(), order.id(), UUID.randomUUID(), "REQUESTED", at, null, null, null, null, true, at,
            at)).as("취소에는 승인 칸이 없다").isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(cancellation, IDS.incrementAndGet(), order.id(), UUID.randomUUID(), "REJECTED", null, "취소 거절", 9, at, null, null, at, at))
            .as("취소 거절 결정(A4)은 실패 코드 없이 받는다").isEqualTo(1);
    }

    /** 고객 반품 접수(첫 항목 1개)를 거쳐 저장된 클레임을 읽는다. */
    private Claim requestReturn(Order order) {
        UUID key = UUID.randomUUID();
        facade.request(7, order.id(), key, command(order, 1));
        return claimAdapter.findByOrderIdAndRequestKey(order.id(), key).orElseThrow();
    }

    private static Claim returnClaim(Order order, Claim.Status status, long orderItemId) {
        boolean approved = status != Claim.Status.REQUESTED && status != Claim.Status.REJECTED;
        return new Claim(IDS.incrementAndGet(), order.id(), UUID.randomUUID(), Claim.Type.RETURN, status, approved ? krw(1_500) : null, approved ? 77L : null, 0,
            Claim.Type.RETURN.isAwaitingAttempt(status) ? NOW : null, status.isFailed() ? "PAYMENT_016" : null, null,
            approved ? new Claim.ReturnApproval(true, krw(500), 9, NOW) : null,
            List.of(new ClaimItem(IDS.incrementAndGet(), orderItemId, 1, ClaimItem.Reason.CHANGE_OF_MIND)), null, null);
    }

    protected void assertRollback(Order order) {
        assertThat(read(order.id()).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(count("claim", order.id())).isZero();
        assertThat(count("order_status_history", order.id())).isEqualTo(1);
    }
}
