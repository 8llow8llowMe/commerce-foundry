package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.KR;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderItemCustomOptionRepository;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderItemRepository;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderRepository;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderShippingAddressRepository;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderStatusHistoryRepository;
import com.commercefoundry.domainlayer.order.application.exception.OrderSaveException;
import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.sql.SQLException;
import java.time.Instant;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 주문 저장 실패를 어느 사유로 바꾸는지 — 제약 이름으로 가리고, 원인 메시지(행 값)는 싣지 않는다. 실제 제약 위반은 리포지토리 테스트(PostgreSQL)가 본다.
 */
class OrderSaveExceptionTest {

    private static final String FAILING_ROW = "Failing row contains (테스트수령인, 010-0000-0001, 테스트시 테스트로 1)";

    private static DataIntegrityViolationException violation(String constraintName) {
        return new DataIntegrityViolationException(FAILING_ROW, new ConstraintViolationException(FAILING_ROW, new SQLException(FAILING_ROW), constraintName));
    }

    private static Order order() {
        return Order.place(1L, 7L, domesticQuote(quoteLine(9001, 10_000, 1)), () -> 11L, address(KR), 99L, Instant.parse("2026-10-18T09:30:00Z"));
    }

    private static OrderRepositoryAdapter adapter(OrderRepository orderRepository) {
        return new OrderRepositoryAdapter(orderRepository, mock(OrderShippingAddressRepository.class), mock(OrderItemRepository.class),
            mock(OrderItemCustomOptionRepository.class), mock(OrderStatusHistoryRepository.class), new OrderMapperImpl(), new SnowflakeIdGenerator(1, 1));
    }

    private static void assertReason(Runnable call, OrderSaveException.Reason reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(OrderSaveException.class, exception -> {
            assertThat(exception.getReason()).isEqualTo(reason);
            assertThat(exception.getCause()).as("원인 예외를 싣지 않는다").isNull();
            assertThat(exception.getMessage()).doesNotContain("테스트수령인", "010-0000-0001");
        });
    }

    @Test
    @DisplayName("주문 PK 위반만 ALREADY_SAVED 다 — 같은 주문 아이디를 다른 시도가 먼저 저장했다")
    void alreadySaved() {
        OrderRepository repository = mock(OrderRepository.class);
        Order order = order();

        willThrow(violation("orders_pkey")).given(repository).saveAndFlush(any());
        assertReason(() -> adapter(repository).insert(order, OrderStatusHistory.created(5L, order)), OrderSaveException.Reason.ALREADY_SAVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders.uk_orders_stock_reservation_id", "ck_orders_total_amount_consistent", "order_item_pkey"})
    @DisplayName("그 밖의 제약 위반은 REJECTED 다 — 다른 주문의 선점 중복도 거절하고 로그에는 주문 아이디와 예외 타입만 남긴다")
    @ExtendWith(OutputCaptureExtension.class)
    void rejected(String constraintName, CapturedOutput output) {
        OrderRepository repository = mock(OrderRepository.class);
        Order order = order();

        willThrow(violation(constraintName)).given(repository).saveAndFlush(any());
        assertReason(() -> adapter(repository).insert(order, OrderStatusHistory.created(5L, order)), OrderSaveException.Reason.REJECTED);
        assertThat(output.getAll()).contains("order save rejected. orderId=1", "type=DataIntegrityViolationException").doesNotContain("Failing row", "010-0000-0001");
    }

    @Test
    @DisplayName("이름 없는 위반도 REJECTED 다 — 로그에는 주문 아이디와 예외 타입만 남긴다")
    @ExtendWith(OutputCaptureExtension.class)
    void rejectedWithoutName(CapturedOutput output) {
        OrderRepository repository = mock(OrderRepository.class);
        Order order = order();

        willThrow(new DataIntegrityViolationException(FAILING_ROW)).given(repository).saveAndFlush(any());
        assertReason(() -> adapter(repository).insert(order, OrderStatusHistory.created(5L, order)), OrderSaveException.Reason.REJECTED);
        assertThat(output.getAll()).contains("type=DataIntegrityViolationException").doesNotContain("Failing row");
    }
}
