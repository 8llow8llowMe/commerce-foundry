package com.commercefoundry.domainlayer.order.support;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

import com.commercefoundry.domainlayer.order.adapter.in.event.PaymentApprovedEventListener;
import com.commercefoundry.domainlayer.order.adapter.out.event.OrderEventAdapter;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.OrderRepositoryAdapter;
import com.commercefoundry.domainlayer.order.application.command.PaymentApprovalCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.model.StockConfirmation;
import com.commercefoundry.domainlayer.order.application.model.VerifiedPayment;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockReservationPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockConfirmationQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import com.commercefoundry.domainlayer.order.application.service.OrderPaymentEventFacade;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentProcessor;
import com.commercefoundry.domainlayer.order.domain.model.*;
import com.commercefoundry.global.messaging.JpaProcessedEventStore;
import com.commercefoundry.messaging.consume.MessagingErrorHandlers;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.properties.MessagingProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 실제 DB 트랜잭션의 원자성·경합 계약을 H2/PostgreSQL에서 공유한다. */
public abstract class OrderPaymentConsumerContract {

    public static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final AtomicLong IDS = new AtomicLong(8_000_000);
    private static final String GROUP = "commerce-foundry.test.order.payment-approved";
    @Autowired protected OrderRepositoryPort orders;
    @Autowired protected OrderPaymentRepositoryPort payments;
    @Autowired protected JpaProcessedEventStore inbox;
    @Autowired protected OrderPaymentEventFacade facade;
    @Autowired protected OrderPaymentProcessor processor;
    @Autowired protected PaymentQueryPort remotePayment;
    @Autowired protected StockReservationPort stock;
    @Autowired protected StockRestorationPort restorations;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired protected PaymentApprovedEventListener listener;
    @MockitoSpyBean protected OrderEventAdapter events;
    @MockitoSpyBean protected OrderRepositoryAdapter orderWrites;

    protected abstract String bootstrapServers();

    @BeforeEach
    void resetPorts() {
        reset(remotePayment, stock, restorations, events, orderWrites);
    }

    @Test
    void kafkaDuplicateAndReplayThroughRealListenerAndBusinessTransactionsCommitExactlyOnce() throws Exception {
        PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
        assertThat(mockingDetails(facade).isMock()).isFalse();
        assertThat(mockingDetails(processor).isMock()).isFalse();
        long reservationId = order(command).stockReservationId();
        AtomicInteger remoteCallsOutsideTransactions = new AtomicInteger();
        when(remotePayment.findPayment(command.paymentId())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            remoteCallsOutsideTransactions.incrementAndGet();
            return Optional.of(new PaymentQueryResult(command.paymentId(), command.orderId(), command.memberId(), "DONE", command.amount(), NOW, "CARD"));
        });
        when(stock.confirm(reservationId)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            remoteCallsOutsideTransactions.incrementAndGet();
            return new StockConfirmationQueryResult(reservationId, command.orderId());
        });
        String topic = "commerce-foundry.test.payment.payment-approved-e2e-" + UUID.randomUUID();
        var codec = new EventEnvelopeCodec(65536);
        var envelope = new EventEnvelope(command.eventId(), "payment-approved", 1, NOW, "payment", "payment", Long.toString(command.orderId()),
            codec.toTree(new PaymentApprovedEventListener.PaymentApprovedMessage(Long.toString(command.paymentId()), Long.toString(command.orderId()),
                Long.toString(command.memberId()), command.amount().amount(), command.amount().currency().name(), NOW, "CARD")));
        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of("bootstrap.servers", bootstrapServers()),
            new StringSerializer(), new StringSerializer());
        var kafka = new KafkaTemplate<>(producerFactory);
        var consumers = new DefaultKafkaConsumerFactory<String, String>(Map.of("bootstrap.servers", bootstrapServers(), "group.id", GROUP,
            "auto.offset.reset", "earliest", "enable.auto.commit", false, "max.poll.records", 1), new StringDeserializer(), new StringDeserializer());
        AtomicInteger deliveries = new AtomicInteger();
        ConcurrentMessageListenerContainer<String, String> container = null;
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1), new NewTopic(topic + ".dlt", 1, (short) 1))).all().get();
            container = startConsumer(topic, consumers, kafka, deliveries);
            kafka.send(topic, envelope.aggregateId(), codec.encode(envelope)).get();
            kafka.send(topic, envelope.aggregateId(), codec.encode(envelope)).get();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(deliveries).hasValue(2));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get();
                assertThat(offsets.get(new TopicPartition(topic, 0)).offset()).isGreaterThanOrEqualTo(2);
            });
            assertSingleReflection(command, codec);
            container.stop(); // 실제 offset commit 뒤 리스너 컨테이너를 새로 만들고 같은 봉투를 재전송한다.
            container = startConsumer(topic, consumers, kafka, deliveries);
            kafka.send(topic, envelope.aggregateId(), codec.encode(envelope)).get();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(deliveries).hasValue(3));
            assertSingleReflection(command, codec);
            assertThat(remoteCallsOutsideTransactions).hasValue(2); // 첫 처리의 payment 조회 + stock 확정만
            verify(remotePayment, times(1)).findPayment(command.paymentId());
            verify(stock, times(1)).confirm(reservationId);
        } finally {
            if (container != null) {
                container.stop();
            }
            producerFactory.destroy();
        }
    }

    protected ConcurrentMessageListenerContainer<String, String> startConsumer(String topic, DefaultKafkaConsumerFactory<String, String> consumers,
        KafkaTemplate<String, String> kafka, AtomicInteger deliveries) {
        return startConsumer(topic, consumers, kafka, deliveries, failure -> {});
    }

    protected ConcurrentMessageListenerContainer<String, String> startConsumer(String topic, DefaultKafkaConsumerFactory<String, String> consumers,
        KafkaTemplate<String, String> kafka, AtomicInteger deliveries, java.util.function.Consumer<RuntimeException> onFailure) {
        var properties = new ContainerProperties(topic);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setMessageListener((MessageListener<String, String>) record -> {
            try {
                listener.onPaymentApproved(record); // 실제 listener bean → 실제 UseCase Spring proxy → 실제 DB
            } catch (RuntimeException failure) {
                onFailure.accept(failure);
                throw failure;
            }
            deliveries.incrementAndGet();
        });
        var container = new ConcurrentMessageListenerContainer<>(consumers, properties);
        container.setCommonErrorHandler(MessagingErrorHandlers.create(kafka,
            new MessagingProperties.Retry(3, Duration.ofMillis(50), 2, Duration.ofMillis(100)), null));
        container.start();
        return container;
    }

    protected void assertSingleReflection(PaymentApprovalCommand command, EventEnvelopeCodec codec) {
        assertThat(order(command).status()).isEqualTo(OrderStatus.PAID);
        assertThat(payment(command).result()).isEqualTo(OrderPayment.Result.PAID);
        assertThat(jdbc.queryForObject("select count(*) from orders.order_payment where payment_id=?", Long.class, command.paymentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from orders.order_status_history where order_id=? and to_status='PAID'",
            Long.class, command.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from orders.processed_event where consumer_group=? and event_id=?",
            Long.class, GROUP, command.eventId())).isEqualTo(1);
        assertThat(replies(command.orderId())).isEqualTo(1);
        String payload = jdbc.queryForObject("select payload from orders.outbox where aggregate_id=?", String.class, Long.toString(command.orderId()));
        EventEnvelope reply = codec.decode(payload);
        assertThat(reply.eventType()).isEqualTo("order-paid");
        assertThat(reply.aggregateId()).isEqualTo(Long.toString(command.orderId()));
        assertThat(reply.payload().get("paymentId").asString()).isEqualTo(Long.toString(command.paymentId()));
        assertThat(reply.payload().get("stockReservationId").asString()).isEqualTo(Long.toString(order(command).stockReservationId()));
    }

    @Test
    void paidAndReplyCommitOnceAcrossAckRestartAndInboxRetention() {
        PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
        facade.approve(command);
        facade.approve(command); // 커밋 뒤 ack 전 재시작의 동일 이벤트 재전달
        jdbc.update("delete from orders.processed_event where event_id = ?", command.eventId());
        facade.approve(command); // inbox 7d 정리 후
        facade.approve(withEvent(command, UUID.randomUUID())); // 다른 eventId, 같은 paymentId
        assertThat(order(command).status()).isEqualTo(OrderStatus.PAID);
        assertThat(payment(command).result()).isEqualTo(OrderPayment.Result.PAID);
        assertThat(inbox.exists(GROUP, command.eventId())).isTrue();
        assertThat(replies(command.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from orders.order_status_history where order_id=? and to_status='PAID'",
            Long.class, command.orderId())).isEqualTo(1);
        verify(stock, times(1)).confirm(anyLong());
    }

    @Test
    void outboxFailureRollsBackOrderHistoryPaymentAndInbox() {
        PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
        doThrow(new IllegalStateException("outbox unavailable")).when(events).paid(any(), any());
        assertThatThrownBy(() -> facade.approve(command)).isInstanceOf(IllegalStateException.class);
        assertThat(order(command).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(jdbc.queryForObject("select count(*) from orders.order_status_history where order_id=? and reason='PAYMENT_APPROVED'",
            Long.class, command.orderId())).isZero();
        assertThat(payments.findByPaymentId(command.paymentId())).isEmpty();
        assertThat(inbox.exists(GROUP, command.eventId())).isFalse();
        assertThat(replies(command.orderId())).isZero();
        reset(events);
        facade.approve(command); // remote CONFIRMED는 다시 확인해도 멱등
        assertThat(order(command).status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void canceledFirstNeverConfirmsAndApprovedFirstRejectsPrepaymentCancel() {
        PaymentApprovalCommand canceled = arrange(OrderStatus.CANCELED);
        long reservationId = order(canceled).stockReservationId();
        facade.approve(canceled);
        assertThat(payment(canceled).result()).isEqualTo(OrderPayment.Result.REJECTED_ORDER_CANCELED);
        assertThat(payment(canceled).stockReservationId()).isNull();
        assertThat(payment(canceled).restorationClaimId()).isNull();
        assertThat(replies(canceled.orderId())).isEqualTo(1);
        // 확정 전 취소 — 해제 프로브가 200(RESERVED 를 풀었거나 이미 RELEASED)이면 확정 · 재선점 · 복원 없이 거절한다
        verify(stock).release(reservationId, canceled.orderId());
        verify(stock, never()).confirm(anyLong());
        verify(stock, never()).reReserve(anyLong(), any());
        verifyNoInteractions(restorations);
        PaymentApprovalCommand paid = arrange(OrderStatus.PENDING_PAYMENT);
        facade.approve(paid);
        assertThatThrownBy(() -> order(paid).cancelBeforePayment()).isInstanceOf(OrderStatusTransitionException.class);
    }

    @Test
    void cancelAfterRemoteConfirmationPreservesReservationForC() {
        PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
        long reservationId = order(command).stockReservationId();
        when(stock.confirm(reservationId)).thenAnswer(invocation -> {
            jdbc.update("update orders.orders set status='CANCELED',version=version+1 where id=?", command.orderId());
            return new StockConfirmationQueryResult(reservationId, command.orderId());
        });
        facade.approve(command);
        assertThat(order(command).status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(payment(command).result()).isEqualTo(OrderPayment.Result.REJECTED_ORDER_CANCELED);
        assertThat(payment(command).stockReservationId()).isEqualTo(reservationId);
        assertRestoredOnce(command, reservationId);
        verify(stock, never()).release(anyLong(), anyLong()); // 이번 처리가 확정 200 을 받았으므로 프로브하지 않는다
    }

    @Test
    void parentLockSerializesPaymentAheadOfStaleCustomerCancelAndVersionRejectsCancel() throws Exception {
        PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
        long reservationId = order(command).stockReservationId();
        CountDownLatch paymentLocked = new CountDownLatch(1);
        CountDownLatch cancelSnapshotRead = new CountDownLatch(1);
        OrderRepositoryAdapter target = AopTestUtils.getUltimateTargetObject(orderWrites);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var cancelAttempt = executor.submit(() -> {
                try {
                    assertThat(paymentLocked.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    Order before = orders.findById(command.orderId()).orElseThrow();
                    assertThat(before.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
                    cancelSnapshotRead.countDown();
                    Order canceled = before.cancelBeforePayment();
                    orders.saveCustomerCancel(canceled,
                        OrderStatusHistory.canceledByCustomer(IDS.incrementAndGet(), before, OrderCancelReason.CHANGE_OF_MIND));
                    events.canceled(before.id(), before.stockReservationId(), OrderCancelReason.CHANGE_OF_MIND);
                });
            });
            try {
                doAnswer(invocation -> {
                    Order changed = invocation.getArgument(0);
                    if (changed.id() == command.orderId()) {
                        paymentLocked.countDown();
                        // 취소의 비잠금 스냅숏 읽기만 기다린다. 부모 잠금이 막는 취소 쓰기 완료를 여기서 기다리면 테스트 자체가 교착된다.
                        assertThat(cancelSnapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
                    }
                    return invocation.callRealMethod();
                }).when(target).saveStatusChange(any(), any());

                facade.approve(command);

                assertThatThrownBy(() -> cancelAttempt.get(20, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(OptimisticLockingFailureException.class);
                assertThat(order(command).status()).isEqualTo(OrderStatus.PAID);
                assertThat(payment(command).result()).isEqualTo(OrderPayment.Result.PAID);
                assertThat(payment(command).stockReservationId()).isEqualTo(reservationId);
                assertThat(inbox.exists(GROUP, command.eventId())).isTrue();
                assertThat(jdbc.queryForObject("select count(*) from orders.order_status_history where order_id=? and to_status='CANCELED'",
                    Long.class, command.orderId())).isZero();
                assertThat(jdbc.queryForObject("select count(*) from orders.order_status_history where order_id=? and reason='PAYMENT_APPROVED'",
                    Long.class, command.orderId())).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from orders.outbox where aggregate_id=? and event_type='order-canceled'",
                    Long.class, Long.toString(command.orderId()))).isZero();
                assertThat(replies(command.orderId())).isEqualTo(1);
                verify(stock, times(1)).confirm(reservationId);
            } finally {
                paymentLocked.countDown();
                reset(target);
            }
        }
    }

    @Test
    void expiredReReservesAndImmediatelyConfirmsBeforePaid() {
        PaymentApprovalCommand command = arrange(OrderStatus.EXPIRED);
        long oldReservation = order(command).stockReservationId();
        long newReservation = IDS.incrementAndGet();
        when(stock.confirm(oldReservation)).thenThrow(new StockReservationException(StockReservationException.Failure.RELEASED));
        when(stock.reReserve(eq(command.orderId()), any())).thenReturn(new StockReservationQueryResult(newReservation, command.orderId(), NOW.minusSeconds(1)));
        when(stock.confirm(newReservation)).thenReturn(new StockConfirmationQueryResult(newReservation, command.orderId()));
        facade.approve(command);
        var sequence = inOrder(stock);
        sequence.verify(stock).confirm(oldReservation);
        sequence.verify(stock).reReserve(eq(command.orderId()), any());
        sequence.verify(stock).confirm(newReservation);
        assertThat(order(command).stockReservationId()).isEqualTo(newReservation);
    }

    @Test
    void partialCancellationRepliesButFullCancellationOnlyRecords() {
        for (String status : new String[] {"CANCELED", "PARTIAL_CANCELED"}) {
            PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
            when(remotePayment.findPayment(command.paymentId())).thenReturn(Optional.of(
                new PaymentQueryResult(command.paymentId(), command.orderId(), command.memberId(), status, command.amount(), NOW, "CARD")));
            facade.approve(command);
            assertThat(payment(command).result()).isEqualTo(OrderPayment.Result.REJECTED_PAYMENT_NOT_DONE);
            assertThat(replies(command.orderId())).isEqualTo(status.equals("CANCELED") ? 0 : 1);
            assertThat(order(command).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(payment(command).stockReservationId()).isNull();
            assertThat(payment(command).restorationClaimId()).isNull();
            assertThat(inbox.exists(GROUP, command.eventId())).isTrue();
            verify(stock).release(order(command).stockReservationId(), command.orderId());
        }
        verifyNoMoreInteractions(stock);
        verifyNoInteractions(restorations);
    }

    @Test
    void orderMismatchRepliesRatherThanSendingForgedNotificationToDlt() {
        PaymentApprovalCommand original = arrange(OrderStatus.PENDING_PAYMENT);
        PaymentApprovalCommand mismatch = new PaymentApprovalCommand(original.eventId(), original.paymentId(), original.orderId(), original.memberId() + 1,
            original.amount(), NOW, "CARD");
        when(remotePayment.findPayment(mismatch.paymentId())).thenReturn(Optional.of(new PaymentQueryResult(mismatch.paymentId(), mismatch.orderId(),
            mismatch.memberId(), "DONE", mismatch.amount(), NOW, "CARD")));
        facade.approve(mismatch);
        assertThat(payment(mismatch).result()).isEqualTo(OrderPayment.Result.REJECTED_PAYMENT_MISMATCH);
        assertThat(replies(mismatch.orderId())).isEqualTo(1);
        verifyNoInteractions(stock);
    }

    @Test
    void concurrentSameEventCommitsOnePaymentAndOneReply() throws Exception {
        concurrent(false, false);
    }

    @Test
    void concurrentDifferentEventsForSamePaymentCommitOneReply() throws Exception {
        concurrent(true, false);
    }

    @Test
    void concurrentDifferentPaymentsForOrderProduceOnePaidAndOneRejectionWithoutReservation() throws Exception {
        concurrent(true, true);
    }

    private void concurrent(boolean otherEvent, boolean otherPayment) throws Exception {
        PaymentApprovalCommand first = arrange(OrderStatus.PENDING_PAYMENT);
        PaymentApprovalCommand second = new PaymentApprovalCommand(otherEvent ? UUID.randomUUID() : first.eventId(),
            otherPayment ? IDS.incrementAndGet() : first.paymentId(), first.orderId(), first.memberId(), first.amount(), NOW, "CARD");
        when(remotePayment.findPayment(second.paymentId())).thenReturn(Optional.of(new PaymentQueryResult(second.paymentId(), second.orderId(),
            second.memberId(), "DONE", second.amount(), NOW, "CARD")));
        CountDownLatch bothConfirmed = new CountDownLatch(2);
        long reservationId = order(first).stockReservationId();
        when(stock.confirm(reservationId)).thenAnswer(call -> {
            bothConfirmed.countDown();
            assertThat(bothConfirmed.await(10, TimeUnit.SECONDS)).isTrue();
            return new StockConfirmationQueryResult(reservationId, first.orderId());
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> approveOrAwaitRedelivery(first));
            var b = executor.submit(() -> approveOrAwaitRedelivery(second));
            boolean retryFirst = a.get(30, TimeUnit.SECONDS);
            boolean retrySecond = b.get(30, TimeUnit.SECONDS);
            assertThat(retryFirst && retrySecond).isFalse();
            // 둘 다 종료한 시점에 승자가 커밋됐음을 확인하고, 정확히 NOWAIT 재시도 소진으로 실패한 전달만 한 번 재전달한다.
            assertThat(payments.findPaidByOrderId(first.orderId())).isPresent();
            assertThat(jdbc.queryForObject("select count(*) from orders.order_payment where order_id=? and result='PAID'",
                Long.class, first.orderId())).isEqualTo(1);
            if (otherEvent && retryFirst) {
                assertThat(inbox.exists(GROUP, first.eventId())).isFalse();
            }
            if (otherEvent && retrySecond) {
                assertThat(inbox.exists(GROUP, second.eventId())).isFalse();
            }
            if (retryFirst) {
                facade.approve(first);
            }
            if (retrySecond) {
                facade.approve(second);
            }
        }
        assertThat(order(first).status()).isEqualTo(OrderStatus.PAID);
        assertThat(replies(first.orderId())).isEqualTo(otherPayment ? 2 : 1);
        assertThat(jdbc.queryForObject("select count(*) from orders.order_payment where order_id=?", Long.class, first.orderId()))
            .isEqualTo(otherPayment ? 2 : 1);
        assertThat(jdbc.queryForObject("select count(*) from orders.order_status_history where order_id=? and to_status='PAID'",
            Long.class, first.orderId())).isEqualTo(1);
        assertThat(inbox.exists(GROUP, first.eventId())).isTrue();
        assertThat(inbox.exists(GROUP, second.eventId())).isTrue();
        verify(stock, times(2)).confirm(reservationId);
        verifyNoInteractions(restorations);
        if (otherPayment) {
            OrderPayment loser = payment(first).result() == OrderPayment.Result.PAID ? payment(second) : payment(first);
            assertThat(loser.result()).isEqualTo(OrderPayment.Result.REJECTED_ALREADY_PAID);
            assertThat(loser.stockReservationId()).isNull();
        }
    }

    private boolean approveOrAwaitRedelivery(PaymentApprovalCommand command) {
        try {
            facade.approve(command);
            return false;
        } catch (IllegalStateException exception) {
            if (!"order payment conflict retry exhausted".equals(exception.getMessage())) {
                throw exception;
            }
            return true;
        }
    }

    // --- PR-C1: 확정 뒤 취소가 이긴 건의 복구창 · 멱등 복원 ---

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,REJECTED_ORDER_CANCELED", "true,REJECTED_ORDER_CANCELED",
        "false,REJECTED_PAYMENT_NOT_DONE", "true,REJECTED_PAYMENT_NOT_DONE"})
    void anotherPaymentsPendingOrCompletedIntentFencesInitialReservation(boolean restored, OrderPayment.Result result) {
        PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
        long reservationId = order(command).stockReservationId();
        // C1 fixture와 C2b NOT_DONE 의도 모두 대기·완료 후 다른 결제의 재확정을 막는다.
        insertIntent(command, reservationId, restored, result);

        facade.approve(command);

        assertThat(payment(command).result()).isEqualTo(OrderPayment.Result.REJECTED_STOCK_UNAVAILABLE);
        assertThat(payment(command).stockReservationId()).isNull();
        assertThat(order(command).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(payments.findPaidByOrderId(command.orderId())).isEmpty();
        assertThat(payments.existsRestorationIntent(command.orderId(), reservationId)).isTrue();
        assertThat(payments.existsRestorationIntent(command.orderId(), reservationId + 1)).isFalse();
        assertThat(payments.existsRestorationIntent(command.orderId() + 1, reservationId)).isFalse();
        verify(stock, never()).confirm(reservationId);
        verify(stock, never()).reReserve(anyLong(), any());
        verifyNoInteractions(restorations);
    }

    @Test
    void reReservationReturningAnotherPaymentsFencedIdIsNotConfirmed() {
        PaymentApprovalCommand command = arrange(OrderStatus.EXPIRED);
        long original = order(command).stockReservationId();
        long replacement = IDS.incrementAndGet();
        // 확장용 영속 fixture: 재선점 API가 과거 복원된 CONFIRMED 선점을 돌려줘도 200 확정을 신뢰하지 않는다.
        insertIntent(command, replacement, true);
        when(stock.confirm(original)).thenThrow(new StockReservationException(StockReservationException.Failure.RELEASED));
        when(stock.reReserve(eq(command.orderId()), any())).thenReturn(new StockReservationQueryResult(replacement, command.orderId(), NOW));

        facade.approve(command);

        assertThat(payment(command).result()).isEqualTo(OrderPayment.Result.REJECTED_STOCK_UNAVAILABLE);
        verify(stock, never()).confirm(replacement);
        assertThat(jdbc.queryForObject("select stock_reservation_id from orders.order_stock_confirmation where payment_id=?",
            Long.class, command.paymentId())).isEqualTo(replacement);
    }

    @Test
    void delayedConfirmAfterIntentCommitCannotRecordPaid() throws Exception {
        PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
        long reservationId = order(command).stockReservationId();
        CountDownLatch confirming = new CountDownLatch(1);
        CountDownLatch returnConfirmed = new CountDownLatch(1);
        when(stock.confirm(reservationId)).thenAnswer(call -> {
            confirming.countDown();
            assertThat(returnConfirmed.await(10, TimeUnit.SECONDS)).isTrue();
            return new StockConfirmationQueryResult(reservationId, command.orderId());
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var delayed = executor.submit(() -> facade.approve(command));
            try {
                assertThat(confirming.await(10, TimeUnit.SECONDS)).isTrue();
                // PENDING+의도 확장 fixture. 사전검사 후 의도 커밋과 늦은 200 사이의 최종 DB fence를 실제 두 트랜잭션으로 검증한다.
                insertIntent(command, reservationId, true);
            } finally {
                returnConfirmed.countDown();
            }
            delayed.get(20, TimeUnit.SECONDS);
        }

        assertThat(order(command).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(payment(command).result()).isEqualTo(OrderPayment.Result.REJECTED_STOCK_UNAVAILABLE);
        assertThat(payments.findPaidByOrderId(command.orderId())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void canceledOrderDoesNotDuplicateAnotherPaymentsRestorationClaim(boolean restored) {
        PaymentApprovalCommand command = arrange(OrderStatus.CANCELED);
        long reservationId = order(command).stockReservationId();
        insertIntent(command, reservationId, restored);
        doThrow(new StockReservationException(StockReservationException.Failure.CONFIRMED)).when(stock).release(reservationId, command.orderId());

        facade.approve(command);

        assertThat(payment(command).result()).isEqualTo(OrderPayment.Result.REJECTED_ORDER_CANCELED);
        assertThat(payment(command).restorationClaimId()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from orders.order_payment where order_id=? and restoration_claim_id is not null",
            Long.class, command.orderId())).isEqualTo(1);
        verifyNoInteractions(restorations);
    }

    /** fixture도 실서비스와 같은 orders → order_payment 잠금/쓰기 순서를 지킨다. product DB 수량을 검증하는 테스트는 아니다. */
    protected void insertIntent(PaymentApprovalCommand command, long reservationId, boolean restored) {
        insertIntent(command, reservationId, restored, OrderPayment.Result.REJECTED_ORDER_CANCELED);
    }

    private void insertIntent(PaymentApprovalCommand command, long reservationId, boolean restored, OrderPayment.Result result) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            orders.findByIdAndMemberIdForUpdate(command.orderId(), command.memberId()).orElseThrow();
            OrderPayment intent = payments.insert(OrderPayment.rejectedAfterConfirmation(IDS.incrementAndGet(), command.orderId(), IDS.incrementAndGet(),
                command.memberId(), command.amount(), NOW, result, reservationId, UUID.randomUUID(), IDS.incrementAndGet()));
            if (restored) {
                payments.markRestored(intent.id(), NOW);
            }
        });
    }

    @Test
    void crashAfterConfirmationThenCancelIsFoundByReleaseProbeAndRestoredOnce() {
        PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
        long reservationId = order(command).stockReservationId();
        cancel(command); // 확정 200 뒤 DB 기록 전에 끊겼고 그 사이 고객 취소가 커밋됐다 — 메모리의 확정 결과는 없다
        doThrow(new StockReservationException(StockReservationException.Failure.CONFIRMED)).when(stock).release(reservationId, command.orderId());

        facade.approve(command);

        assertThat(payment(command).result()).isEqualTo(OrderPayment.Result.REJECTED_ORDER_CANCELED);
        assertThat(payment(command).stockReservationId()).isEqualTo(reservationId);
        assertRestoredOnce(command, reservationId);
        verify(stock, never()).confirm(anyLong());
        verify(stock, never()).reReserve(anyLong(), any());

        facade.approve(command); // 같은 이벤트 재전달 — inbox
        facade.approve(withEvent(command, UUID.randomUUID())); // 다른 eventId — 복원이 끝난 기록이라 회신을 다시 쌓지 않는다
        assertRestoredOnce(command, reservationId);
        verify(restorations, times(1)).restoreCancellation(anyLong(), anyLong(), any());
    }

    @Test
    void restorationFailureResumesWithSameClaimOnRedeliveryAndRepliesOnce() {
        PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
        long reservationId = order(command).stockReservationId();
        cancel(command);
        doThrow(new StockReservationException(StockReservationException.Failure.CONFIRMED)).when(stock).release(reservationId, command.orderId());
        doThrow(new StockReservationException(StockReservationException.Failure.UNKNOWN)).doNothing()
            .when(restorations).restoreCancellation(anyLong(), anyLong(), any());

        assertThatThrownBy(() -> facade.approve(command)).isInstanceOf(OrderException.class);
        OrderPayment pending = payment(command);
        assertThat(pending.awaitsRestoration()).isTrue();
        assertThat(inbox.exists(GROUP, command.eventId())).isFalse();
        assertThat(replies(command.orderId())).isZero();

        facade.approve(command); // 재전달 — 프로브 없이 기록의 같은 claimId 로 복원부터 다시 한다

        verify(restorations, times(2)).restoreCancellation(pending.restorationClaimId(), reservationId, skus());
        verify(stock, times(1)).release(anyLong(), anyLong());
        assertThat(payment(command).restorationClaimId()).isEqualTo(pending.restorationClaimId());
        assertRestoredOnce(command, reservationId);
    }

    @Test
    void reReservedIdIsRecordedBeforeConfirmAndProbedAfterCrash() {
        PaymentApprovalCommand command = arrange(OrderStatus.EXPIRED);
        long oldReservation = order(command).stockReservationId();
        long newReservation = IDS.incrementAndGet();
        when(stock.confirm(oldReservation)).thenThrow(new StockReservationException(StockReservationException.Failure.RELEASED));
        when(stock.reReserve(eq(command.orderId()), any())).thenReturn(new StockReservationQueryResult(newReservation, command.orderId(), NOW));
        when(stock.confirm(newReservation)).thenAnswer(invocation -> {
            // 증거는 확정 호출 전에 이미 커밋돼 있다
            assertThat(jdbc.queryForObject("select stock_reservation_id from orders.order_stock_confirmation where payment_id=?", Long.class,
                command.paymentId())).isEqualTo(newReservation);
            cancel(command);
            throw new IllegalStateException("process killed after remote confirmation");
        });
        assertThatThrownBy(() -> facade.approve(command)).isInstanceOf(IllegalStateException.class);
        assertThat(payments.findByPaymentId(command.paymentId())).isEmpty();

        reset(stock);
        doThrow(new StockReservationException(StockReservationException.Failure.CONFIRMED)).when(stock).release(newReservation, command.orderId());
        facade.approve(command);

        var sequence = inOrder(stock);
        sequence.verify(stock).release(newReservation, command.orderId());
        sequence.verify(stock).release(oldReservation, command.orderId());
        verify(stock, never()).reReserve(anyLong(), any());
        assertThat(payment(command).stockReservationId()).isEqualTo(newReservation);
        assertRestoredOnce(command, newReservation);
    }

    @Test
    void quantityExceededLeavesIntentPendingWithoutInboxOrReply() {
        PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
        long reservationId = order(command).stockReservationId();
        cancel(command);
        doThrow(new StockReservationException(StockReservationException.Failure.CONFIRMED)).when(stock).release(reservationId, command.orderId());
        doThrow(new StockReservationException(StockReservationException.Failure.QUANTITY_EXCEEDED)).when(restorations)
            .restoreCancellation(anyLong(), anyLong(), any());

        // product는 일부 SKU/수량만 외부 복원됐어도 요청 전체를 028로 거절한다. mock 오류를 전량 복원의 증거로 삼지 않는다.
        assertThatThrownBy(() -> facade.approve(command)).isInstanceOf(NonRetryableEventException.class);

        assertThat(payment(command).awaitsRestoration()).isTrue();
        assertThat(payment(command).restoredAt()).isNull();
        assertThat(inbox.exists(GROUP, command.eventId())).isFalse();
        assertThat(replies(command.orderId())).isZero();
    }

    @Test
    void mismatchOrNotConfirmedRestorationGoesToDltWithoutReply() {
        for (StockReservationException.Failure failure
            : new StockReservationException.Failure[] {StockReservationException.Failure.RESTORATION_MISMATCH, StockReservationException.Failure.NOT_CONFIRMED,
                StockReservationException.Failure.NOT_FOUND}) {
            PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
            long reservationId = order(command).stockReservationId();
            cancel(command);
            doThrow(new StockReservationException(StockReservationException.Failure.CONFIRMED)).when(stock).release(reservationId, command.orderId());
            doThrow(new StockReservationException(failure)).when(restorations).restoreCancellation(anyLong(), anyLong(), any());

            assertThatThrownBy(() -> facade.approve(command)).isInstanceOf(NonRetryableEventException.class);
            assertThat(payment(command).awaitsRestoration()).isTrue();
            assertThat(replies(command.orderId())).isZero();
            assertThat(inbox.exists(GROUP, command.eventId())).isFalse();
        }
    }

    @Test
    void confirmedRunLosingToProbeRejectionGoesToDltWithoutExtraReply() {
        PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
        long reservationId = order(command).stockReservationId();
        PaymentApprovalCommand other = withEvent(command, UUID.randomUUID());
        when(stock.confirm(reservationId)).thenAnswer(invocation -> {
            // 확정 응답 전에 고객 취소가 커밋되고, 같은 결제의 다른 전달이 프로브 200(확정 전 취소로 봄)으로 거절을 먼저 커밋한다
            cancel(command);
            facade.approve(other);
            return new StockConfirmationQueryResult(reservationId, command.orderId());
        });

        assertThatThrownBy(() -> facade.approve(command)).isInstanceOf(NonRetryableEventException.class);

        assertThat(payment(command).stockReservationId()).isNull();
        assertThat(replies(command.orderId())).isEqualTo(1); // 먼저 커밋된 거절 회신뿐 — 추가 회신 없음
        assertThat(inbox.exists(GROUP, command.eventId())).isFalse();
        assertThat(inbox.exists(GROUP, other.eventId())).isTrue();
        verifyNoInteractions(restorations);
    }

    @Test
    void canceledOrderPaidByAnotherPaymentIsNotRestored() {
        PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
        long reservationId = order(command).stockReservationId();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> payments.insert(new OrderPayment(IDS.incrementAndGet(), command.orderId(),
            IDS.incrementAndGet(), command.memberId(), command.amount(), NOW, OrderPayment.Result.PAID, reservationId, UUID.randomUUID(), null, null, null)));
        cancel(command); // 다른 결제로 결제 완료된 뒤 취소된 주문 — 확정 선점은 이긴 결제의 것이고 그 취소 클레임이 복원한다
        doThrow(new StockReservationException(StockReservationException.Failure.CONFIRMED)).when(stock).release(reservationId, command.orderId());

        facade.approve(command);

        assertThat(payment(command).result()).isEqualTo(OrderPayment.Result.REJECTED_ORDER_CANCELED);
        assertThat(payment(command).stockReservationId()).isNull();
        assertThat(payment(command).restorationClaimId()).isNull();
        assertThat(replies(command.orderId())).isEqualTo(1);
        verifyNoInteractions(restorations);
    }

    @Test
    void resumedRestorationAfterFullPaymentCancelDoesNotReply() {
        PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
        long reservationId = order(command).stockReservationId();
        cancel(command);
        doThrow(new StockReservationException(StockReservationException.Failure.CONFIRMED)).when(stock).release(reservationId, command.orderId());
        doThrow(new StockReservationException(StockReservationException.Failure.UNKNOWN)).doNothing()
            .when(restorations).restoreCancellation(anyLong(), anyLong(), any());
        assertThatThrownBy(() -> facade.approve(command)).isInstanceOf(OrderException.class);
        when(remotePayment.findPayment(command.paymentId())).thenReturn(Optional.of(
            new PaymentQueryResult(command.paymentId(), command.orderId(), command.memberId(), "CANCELED", command.amount(), NOW, "CARD")));

        facade.approve(command); // 재전달 사이 결제가 전액 취소됐다 — 복원은 끝내고 환불할 돈이 없으니 회신하지 않는다

        assertThat(payment(command).restoredAt()).isNotNull();
        assertThat(replies(command.orderId())).isZero();
        assertThat(inbox.exists(GROUP, command.eventId())).isTrue();
    }

    // C2b: shared real-order-DB contract, not a product database quantity test.
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "CANCELED,false,PENDING_PAYMENT", "CANCELED,false,EXPIRED", "CANCELED,false,CANCELED",
        "CANCELED,true,PENDING_PAYMENT", "CANCELED,true,EXPIRED", "CANCELED,true,CANCELED",
        "PARTIAL_CANCELED,false,PENDING_PAYMENT", "PARTIAL_CANCELED,false,EXPIRED", "PARTIAL_CANCELED,false,CANCELED",
        "PARTIAL_CANCELED,true,PENDING_PAYMENT", "PARTIAL_CANCELED,true,EXPIRED", "PARTIAL_CANCELED,true,CANCELED"
    })
    void canceledSourceRestoresInitialOrPersistedReReservation(String source, boolean reReserved, OrderStatus status) {
        PaymentApprovalCommand command = arrange(status);
        long original = order(command).stockReservationId();
        long confirmed = reReserved ? IDS.incrementAndGet() : original;
        if (reReserved) {
            processor.recordReReservation(command.paymentId(), command.orderId(), confirmed, command.eventId());
        }
        when(remotePayment.findPayment(command.paymentId())).thenReturn(Optional.of(new PaymentQueryResult(command.paymentId(), command.orderId(),
            command.memberId(), source, command.amount(), NOW, "CARD")));
        doThrow(new StockReservationException(StockReservationException.Failure.CONFIRMED)).when(stock).release(confirmed, command.orderId());

        facade.approve(command);
        OrderPayment first = payment(command);
        assertThat(first.result()).isEqualTo(OrderPayment.Result.REJECTED_PAYMENT_NOT_DONE);
        assertThat(first.stockReservationId()).isEqualTo(confirmed);
        assertThat(first.approvedAt()).isEqualTo(NOW);
        assertThat(first.restoredAt()).isNotNull();
        assertThat(order(command).status()).isEqualTo(status);
        assertThat(replies(command.orderId())).isEqualTo(source.equals("CANCELED") ? 0 : 1);
        facade.approve(command);
        facade.approve(withEvent(command, UUID.randomUUID()));
        assertThat(payment(command)).isEqualTo(first);
        verify(restorations, times(1)).restoreCancellation(first.restorationClaimId(), confirmed, skus());
        verify(stock, never()).confirm(anyLong());
        verify(stock, never()).reReserve(anyLong(), any());
    }

    @Test
    void pendingNotDoneResumesWithOtherSourceAndOriginalClaimAndReason() {
        PaymentApprovalCommand command = arrange(OrderStatus.EXPIRED);
        long reservation = order(command).stockReservationId();
        when(remotePayment.findPayment(command.paymentId())).thenReturn(Optional.of(new PaymentQueryResult(command.paymentId(), command.orderId(),
            command.memberId(), "PARTIAL_CANCELED", command.amount(), NOW, "CARD")));
        doThrow(new StockReservationException(StockReservationException.Failure.CONFIRMED)).when(stock).release(reservation, command.orderId());
        doThrow(new StockReservationException(StockReservationException.Failure.UNKNOWN)).doNothing()
            .when(restorations).restoreCancellation(anyLong(), anyLong(), any());
        assertThatThrownBy(() -> facade.approve(command)).isInstanceOf(OrderException.class);
        OrderPayment pending = payment(command);
        cancel(command);
        when(remotePayment.findPayment(command.paymentId())).thenReturn(Optional.of(new PaymentQueryResult(command.paymentId(), command.orderId(),
            command.memberId(), "UNKNOWN", command.amount(), null, null)));
        facade.approve(command);
        OrderPayment completed = payment(command);
        assertThat(completed.restorationClaimId()).isEqualTo(pending.restorationClaimId());
        assertThat(completed.approvedAt()).isEqualTo(pending.approvedAt());
        assertThat(completed.result()).isEqualTo(OrderPayment.Result.REJECTED_PAYMENT_NOT_DONE);
        assertThat(completed.restoredAt()).isNotNull();
        assertThat(replies(command.orderId())).isEqualTo(1);
        verify(stock, times(1)).release(reservation, command.orderId());
        verify(restorations, times(2)).restoreCancellation(pending.restorationClaimId(), reservation, skus());
    }

    @Test
    void otherSourceCanceledOrderAndNullableLegacyReplayNeverTouchStock() {
        PaymentApprovalCommand command = arrange(OrderStatus.CANCELED);
        when(remotePayment.findPayment(command.paymentId())).thenReturn(Optional.of(new PaymentQueryResult(command.paymentId(), command.orderId(),
            command.memberId(), "UNKNOWN", command.amount(), null, null)));
        facade.approve(command);
        facade.approve(withEvent(command, UUID.randomUUID()));
        assertThat(payment(command).approvedAt()).isNull();
        assertThat(payment(command).result()).isEqualTo(OrderPayment.Result.REJECTED_PAYMENT_NOT_DONE);
        assertThat(replies(command.orderId())).isEqualTo(1);
        verifyNoInteractions(stock, restorations);
    }

    @Test
    void canceledSourceLegacyPaidOrderWithoutPaidRecordIsDltWithoutProbe() {
        PaymentApprovalCommand command = arrange(OrderStatus.MAKING);
        when(remotePayment.findPayment(command.paymentId())).thenReturn(Optional.of(new PaymentQueryResult(command.paymentId(), command.orderId(),
            command.memberId(), "CANCELED", command.amount(), NOW, "CARD")));
        assertThatThrownBy(() -> facade.approve(command)).isInstanceOf(NonRetryableEventException.class);
        assertThat(payments.findByPaymentId(command.paymentId())).isEmpty();
        verifyNoInteractions(stock, restorations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "PARTIAL_CANCELED"})
    void canceledSourceProtectsOwnAndOtherPaidRecords(String source) {
        PaymentApprovalCommand own = arrange(OrderStatus.PENDING_PAYMENT);
        facade.approve(own);
        OrderPayment paid = payment(own);
        reset(stock, restorations);
        when(remotePayment.findPayment(own.paymentId())).thenReturn(Optional.of(new PaymentQueryResult(own.paymentId(), own.orderId(), own.memberId(),
            source, own.amount(), NOW, "CARD")));
        facade.approve(withEvent(own, UUID.randomUUID()));
        assertThat(payment(own)).isEqualTo(paid);
        verifyNoInteractions(stock, restorations);

        PaymentApprovalCommand other = arrange(OrderStatus.CANCELED);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> payments.insert(new OrderPayment(IDS.incrementAndGet(), other.orderId(),
            IDS.incrementAndGet(), other.memberId(), other.amount(), NOW, OrderPayment.Result.PAID, order(other).stockReservationId(), UUID.randomUUID(),
            null, null, null)));
        when(remotePayment.findPayment(other.paymentId())).thenReturn(Optional.of(new PaymentQueryResult(other.paymentId(), other.orderId(), other.memberId(),
            source, other.amount(), NOW, "CARD")));
        facade.approve(other);
        assertThat(payment(other).result()).isEqualTo(OrderPayment.Result.REJECTED_PAYMENT_NOT_DONE);
        assertThat(payment(other).stockReservationId()).isNull();
        verifyNoInteractions(stock, restorations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "amount", "approvedAt"})
    void pendingRestorationRejectsStoredIdentityMismatchBeforeAnyRemoteRestoration(String field) {
        PaymentApprovalCommand command = arrange(OrderStatus.CANCELED);
        long reservation = order(command).stockReservationId();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> payments.insert(OrderPayment.rejectedAfterConfirmation(IDS.incrementAndGet(),
            command.orderId(), command.paymentId(), field.equals("member") ? 8 : command.memberId(), field.equals("amount") ? krw(1001) : command.amount(),
            field.equals("approvedAt") ? NOW.plusSeconds(1) : NOW, OrderPayment.Result.REJECTED_PAYMENT_NOT_DONE, reservation,
            command.eventId(), IDS.incrementAndGet())));
        when(remotePayment.findPayment(command.paymentId())).thenReturn(Optional.of(new PaymentQueryResult(command.paymentId(), command.orderId(),
            command.memberId(), "UNKNOWN", command.amount(), null, null)));
        assertThatThrownBy(() -> facade.approve(command)).isInstanceOf(NonRetryableEventException.class);
        assertThat(inbox.exists(GROUP, command.eventId())).isFalse();
        assertThat(payment(command).restoredAt()).isNull();
        verifyNoInteractions(stock, restorations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "amount", "missing"})
    void canceledSourceValidatesOrderBeforeReturningNotDone(String field) {
        PaymentApprovalCommand command = arrange(OrderStatus.CANCELED);
        if (field.equals("member")) {
            jdbc.update("update orders.orders set member_id=8 where id=?", command.orderId());
        } else if (field.equals("amount")) {
            when(remotePayment.findPayment(command.paymentId())).thenReturn(Optional.of(new PaymentQueryResult(command.paymentId(), command.orderId(),
                command.memberId(), "CANCELED", krw(1001), NOW, "CARD")));
            PaymentApprovalCommand changed = new PaymentApprovalCommand(command.eventId(), command.paymentId(), command.orderId(), command.memberId(),
                krw(1001), NOW, "CARD");
            facade.approve(changed);
            assertThat(payment(command).result()).isEqualTo(OrderPayment.Result.REJECTED_PAYMENT_MISMATCH);
            verifyNoInteractions(stock, restorations);
            return;
        } else {
            jdbc.update("delete from orders.order_item where order_id=?", command.orderId());
            jdbc.update("delete from orders.order_status_history where order_id=?", command.orderId());
            jdbc.update("delete from orders.orders where id=?", command.orderId());
        }
        when(remotePayment.findPayment(command.paymentId())).thenReturn(Optional.of(new PaymentQueryResult(command.paymentId(), command.orderId(),
            command.memberId(), "CANCELED", command.amount(), NOW, "CARD")));
        facade.approve(command);
        assertThat(payment(command).result()).isEqualTo(field.equals("missing") ? OrderPayment.Result.REJECTED_ORDER_NOT_FOUND
            : OrderPayment.Result.REJECTED_PAYMENT_MISMATCH);
        verifyNoInteractions(stock, restorations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "amount", "status", "paid"})
    void pendingNotDoneRevalidatesCurrentOrderBeforeRestoring(String field) {
        PaymentApprovalCommand command = arrange(OrderStatus.EXPIRED);
        long reservation = order(command).stockReservationId();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> payments.insert(OrderPayment.rejectedAfterConfirmation(IDS.incrementAndGet(),
            command.orderId(), command.paymentId(), command.memberId(), command.amount(), NOW, OrderPayment.Result.REJECTED_PAYMENT_NOT_DONE,
            reservation, command.eventId(), IDS.incrementAndGet())));
        switch (field) {
            case "member" -> jdbc.update("update orders.orders set member_id=8 where id=?", command.orderId());
            case "amount" -> {
                jdbc.update("update orders.order_item set unit_price_amount=1001,line_amount=1001 where order_id=?", command.orderId());
                jdbc.update("update orders.orders set items_amount=1001,total_amount=4001 where id=?", command.orderId());
            }
            case "status" -> jdbc.update("update orders.orders set status='MAKING',version=version+1 where id=?", command.orderId());
            default -> new TransactionTemplate(transactions).executeWithoutResult(tx -> payments.insert(new OrderPayment(IDS.incrementAndGet(),
                command.orderId(), IDS.incrementAndGet(), command.memberId(), command.amount(), NOW, OrderPayment.Result.PAID, reservation,
                UUID.randomUUID(), null, null, null)));
        }
        when(remotePayment.findPayment(command.paymentId())).thenReturn(Optional.of(new PaymentQueryResult(command.paymentId(), command.orderId(),
            command.memberId(), "UNKNOWN", command.amount(), null, null)));

        assertThatThrownBy(() -> facade.approve(command)).isInstanceOf(NonRetryableEventException.class);
        assertThat(payment(command).restoredAt()).isNull();
        assertThat(inbox.exists(GROUP, command.eventId())).isFalse();
        assertThat(replies(command.orderId())).isZero();
        verifyNoInteractions(stock, restorations);
    }

    private void cancel(PaymentApprovalCommand command) {
        jdbc.update("update orders.orders set status='CANCELED',version=version+1 where id=?", command.orderId());
    }

    private static SortedMap<Long, Integer> skus() {
        return new TreeMap<>(Map.of(101L, 1));
    }

    /** 복원은 기록의 claimId 로 끝났고(restored_at), 거절 회신 · inbox 는 한 번이다. */
    private void assertRestoredOnce(PaymentApprovalCommand command, long reservationId) {
        OrderPayment record = payment(command);
        assertThat(record.restorationClaimId()).isNotNull();
        assertThat(record.restoredAt()).isNotNull();
        assertThat(record.awaitsRestoration()).isFalse();
        verify(restorations, atLeastOnce()).restoreCancellation(record.restorationClaimId(), reservationId, skus());
        assertThat(jdbc.queryForObject("select count(*) from orders.outbox where aggregate_id=? and event_type='order-payment-rejected'",
            Long.class, Long.toString(command.orderId()))).isEqualTo(1);
        assertThat(inbox.exists(GROUP, command.eventId())).isTrue();
    }

    protected PaymentApprovalCommand arrange(OrderStatus status) {
        long orderId = IDS.incrementAndGet();
        Order order = Order.place(orderId, 7L, domesticQuote(quoteLine(101, 1000, 1)), IDS::incrementAndGet, address("KR"), IDS.incrementAndGet(),
            NOW.plusSeconds(1800));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            orders.insert(order, OrderStatusHistory.created(IDS.incrementAndGet(), order));
            if (status != OrderStatus.PENDING_PAYMENT) {
                jdbc.update("update orders.orders set status=?,version=version+1 where id=?", status.name(), orderId);
            }
        });
        PaymentApprovalCommand command = new PaymentApprovalCommand(UUID.randomUUID(), IDS.incrementAndGet(), orderId, 7L, order.quote().totalAmount(), NOW, "CARD");
        when(remotePayment.findPayment(command.paymentId())).thenReturn(Optional.of(new PaymentQueryResult(command.paymentId(), orderId, 7L,
            "DONE", command.amount(), NOW, "CARD")));
        when(stock.confirm(order.stockReservationId())).thenReturn(new StockConfirmationQueryResult(order.stockReservationId(), orderId));
        return command;
    }

    private PaymentApprovalCommand withEvent(PaymentApprovalCommand c, UUID eventId) {
        return new PaymentApprovalCommand(eventId, c.paymentId(), c.orderId(), c.memberId(), c.amount(), c.approvedAt(), c.method());
    }

    private Order order(PaymentApprovalCommand command) {
        return new TransactionTemplate(transactions).execute(tx -> orders.findById(command.orderId()).orElseThrow());
    }

    private OrderPayment payment(PaymentApprovalCommand command) {
        return payments.findByPaymentId(command.paymentId()).orElseThrow();
    }

    private long replies(long orderId) {
        return jdbc.queryForObject("select count(*) from orders.outbox where aggregate_id=?", Long.class, Long.toString(orderId));
    }
}
