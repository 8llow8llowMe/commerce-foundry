package com.commercefoundry.domainlayer.order.support;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.application.command.OrderCreateCommand;
import com.commercefoundry.domainlayer.order.application.model.StockReservation;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderProcessor;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.OrderSaveException;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 실제 Spring proxy와 H2 / PostgreSQL에서 C3 저장 경계를 통과한다. */
public abstract class OrderCreatePersistenceContract extends OrderRequestPersistenceContract {
    @Autowired protected OrderProcessor orderProcessor;
    @MockitoBean protected Clock clock;
    @MockitoSpyBean protected OrderRepositoryPort orderStorage;

    @BeforeEach
    void clockAtStart() { when(clock.instant()).thenReturn(NOW); }

    private OrderCreateCommand command() {
        return new OrderCreateCommand(List.of(OrderFixtures.line(101, 1)), OrderFixtures.address("KR"), null);
    }

    private StockReservation reservation(OrderRequest request) {
        return new StockReservation(id(), request.orderId(), NOW.plusSeconds(600));
    }

    private OrderRequest owned() {
        return requests.insertIfAbsent(OrderRequest.start(id(), id(), UUID.randomUUID(), command().fingerprint(), id(), NOW, LEASE)).orElseThrow();
    }

    @Test
    void proxiedProcessorRollsBackCompletionAndWholeAggregateAfterLateFailure() {
        var request = owned();
        doAnswer(call -> { call.callRealMethod(); throw new OrderSaveException(OrderSaveException.Reason.REJECTED, "SimulatedFailure"); })
            .when(org.springframework.test.util.AopTestUtils.<OrderRepositoryPort>getUltimateTargetObject(orderStorage)).insert(any(), any());
        assertThatThrownBy(() -> orderProcessor.create(request.memberId(), request, command(),
            OrderFixtures.domesticQuote(OrderFixtures.quoteLine(101, 1000, 1)), reservation(request)))
            .isInstanceOfSatisfying(OrderException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_SAVE_FAILED));
        assertThat(requests.findByMemberIdAndIdempotencyKey(request.memberId(), request.idempotencyKey()).orElseThrow().status())
            .isEqualTo(OrderRequest.Status.PROCESSING);
        assertThat(orders.findByIdAndMemberId(request.orderId(), request.memberId())).isEmpty();
        reset(org.springframework.test.util.AopTestUtils.<OrderRepositoryPort>getUltimateTargetObject(orderStorage));
        var order = orderProcessor.create(request.memberId(), request, command(),
            OrderFixtures.domesticQuote(OrderFixtures.quoteLine(101, 1000, 1)), reservation(request));
        assertThat(orders.findByIdAndMemberId(order.id(), request.memberId())).isPresent();
        assertThat(requests.findByMemberIdAndIdempotencyKey(request.memberId(), request.idempotencyKey()).orElseThrow().status())
            .isEqualTo(OrderRequest.Status.COMPLETED);
    }

    @Test
    void staleAndExpiredOwnersAndExpiredReservationCannotInsert() {
        var request = owned();
        when(clock.instant()).thenReturn(request.leaseExpiresAt());
        assertThatThrownBy(() -> orderProcessor.create(request.memberId(), request, command(),
            OrderFixtures.domesticQuote(OrderFixtures.quoteLine(101, 1000, 1)), reservation(request))).isInstanceOf(OrderException.class);
        requests.takeOver(request, request.leaseExpiresAt(), LEASE).orElseThrow();
        assertThatThrownBy(() -> orderProcessor.create(request.memberId(), request, command(),
            OrderFixtures.domesticQuote(OrderFixtures.quoteLine(101, 1000, 1)), reservation(request))).isInstanceOf(OrderException.class);
        assertThat(orders.findByIdAndMemberId(request.orderId(), request.memberId())).isEmpty();
        var next = owned();
        when(clock.instant()).thenReturn(NOW);
        assertThatThrownBy(() -> orderProcessor.create(next.memberId(), next, command(),
            OrderFixtures.domesticQuote(OrderFixtures.quoteLine(101, 1000, 1)), new StockReservation(id(), next.orderId(), NOW.plusNanos(999))))
            .isInstanceOfSatisfying(OrderException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE));
        verify(org.springframework.test.util.AopTestUtils.<OrderRepositoryPort>getUltimateTargetObject(orderStorage), never()).insert(any(), any());
    }

    @Test
    void differentCommandCannotCompleteAnOwnedRequest() {
        var request = owned();
        var changed = new OrderCreateCommand(List.of(OrderFixtures.line(101, 2)), command().shippingAddress(), null);
        assertThatThrownBy(() -> orderProcessor.create(request.memberId(), request, changed,
            OrderFixtures.domesticQuote(OrderFixtures.quoteLine(101, 1000, 2)), reservation(request))).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests.findByMemberIdAndIdempotencyKey(request.memberId(), request.idempotencyKey()).orElseThrow().status())
            .isEqualTo(OrderRequest.Status.PROCESSING);
        verify(org.springframework.test.util.AopTestUtils.<OrderRepositoryPort>getUltimateTargetObject(orderStorage), never()).insert(any(), any());
    }

    @Test
    void twoOwnersAtSameVersionCanCommitOnlyOneAggregate() throws Exception {
        var request = owned();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> {
                start.await();
                try {
                    orderProcessor.create(request.memberId(), request, command(),
                        OrderFixtures.domesticQuote(OrderFixtures.quoteLine(101, 1000, 1)), reservation(request));
                    return true;
                } catch (OrderException fenced) { return false; }
            };
            var one = pool.submit(attempt); var two = pool.submit(attempt); start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(orders.findByIdAndMemberId(request.orderId(), request.memberId())).isPresent();
    }
}
