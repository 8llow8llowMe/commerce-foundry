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
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    protected void assertRollback(Order order) {
        assertThat(read(order.id()).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(count("claim", order.id())).isZero();
        assertThat(count("order_status_history", order.id())).isEqualTo(1);
    }
}
