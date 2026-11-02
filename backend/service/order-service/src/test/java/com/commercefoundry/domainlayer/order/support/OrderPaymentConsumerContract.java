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
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.model.StockConfirmation;
import com.commercefoundry.domainlayer.order.application.model.VerifiedPayment;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockReservationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockConfirmationQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import com.commercefoundry.domainlayer.order.application.service.OrderPaymentEventFacade;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentProcessor;
import com.commercefoundry.domainlayer.order.domain.model.*;
import com.commercefoundry.global.messaging.JpaProcessedEventStore;
import com.commercefoundry.messaging.consume.MessagingErrorHandlers;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.properties.MessagingProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired protected PaymentApprovedEventListener listener;
    @MockitoSpyBean protected OrderEventAdapter events;
    @MockitoSpyBean protected OrderRepositoryAdapter orderWrites;

    protected abstract String bootstrapServers();

    @BeforeEach
    void resetPorts() {
        reset(remotePayment, stock, events, orderWrites);
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

    private ConcurrentMessageListenerContainer<String, String> startConsumer(String topic, DefaultKafkaConsumerFactory<String, String> consumers,
        KafkaTemplate<String, String> kafka, AtomicInteger deliveries) {
        var properties = new ContainerProperties(topic);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setMessageListener((MessageListener<String, String>) record -> {
            listener.onPaymentApproved(record); // 실제 listener bean → 실제 UseCase Spring proxy → 실제 DB
            deliveries.incrementAndGet();
        });
        var container = new ConcurrentMessageListenerContainer<>(consumers, properties);
        container.setCommonErrorHandler(MessagingErrorHandlers.create(kafka,
            new MessagingProperties.Retry(3, Duration.ofMillis(50), 2, Duration.ofMillis(100)), null));
        container.start();
        return container;
    }

    private void assertSingleReflection(PaymentApprovalCommand command, EventEnvelopeCodec codec) {
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
        facade.approve(canceled);
        assertThat(payment(canceled).result()).isEqualTo(OrderPayment.Result.REJECTED_ORDER_CANCELED);
        verifyNoInteractions(stock);
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
    }

    @Test
    void realVersionConflictAfterOrderReadReplaysInNewTransactionAndPreservesConfirmedReservation() throws Exception {
        PaymentApprovalCommand command = arrange(OrderStatus.PENDING_PAYMENT);
        long reservationId = order(command).stockReservationId();
        AtomicReference<Throwable> actualConflict = new AtomicReference<>();
        AtomicInteger writesIntercepted = new AtomicInteger();
        OrderRepositoryAdapter target = AopTestUtils.getUltimateTargetObject(orderWrites);
        try {
            doAnswer(invocation -> {
                Order changed = invocation.getArgument(0);
                if (changed.id() == command.orderId() && writesIntercepted.getAndIncrement() == 0) {
                    // complete가 주문을 읽고 PAID를 만든 직후 멈춘다. 별도 스레드/tx에서 실제 고객취소와 outbox를 먼저 커밋한다.
                    try (var executor = Executors.newSingleThreadExecutor()) {
                        executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                            Order before = orders.findById(command.orderId()).orElseThrow();
                            Order canceled = before.cancelBeforePayment();
                            orders.saveCustomerCancel(canceled,
                                OrderStatusHistory.canceledByCustomer(IDS.incrementAndGet(), before, OrderCancelReason.CHANGE_OF_MIND));
                            events.canceled(before.id(), before.stockReservationId(), OrderCancelReason.CHANGE_OF_MIND);
                        })).get(10, TimeUnit.SECONDS);
                    }
                    try {
                        return invocation.callRealMethod(); // stale @Version을 실제 saveAndFlush에 전달한다. 실패를 mock하지 않는다.
                    } catch (Throwable conflict) {
                        actualConflict.set(conflict);
                        throw conflict;
                    }
                }
                return invocation.callRealMethod();
            }).when(target).saveStatusChange(any(), any());

            facade.approve(command);

            assertThat(actualConflict.get()).isInstanceOf(OptimisticLockingFailureException.class);
            assertThat(writesIntercepted).hasValue(1); // 재판정에서 취소를 읽었으므로 두 번째 PAID 쓰기는 없다.
            assertThat(order(command).status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(payment(command).result()).isEqualTo(OrderPayment.Result.REJECTED_ORDER_CANCELED);
            assertThat(payment(command).stockReservationId()).isEqualTo(reservationId);
            assertThat(inbox.exists(GROUP, command.eventId())).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from orders.order_status_history where order_id=? and to_status='CANCELED'",
                Long.class, command.orderId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from orders.order_status_history where order_id=? and reason='PAYMENT_APPROVED'",
                Long.class, command.orderId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from orders.outbox where aggregate_id=? and event_type='order-canceled'",
                Long.class, Long.toString(command.orderId()))).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from orders.outbox where aggregate_id=? and event_type='order-payment-rejected'",
                Long.class, Long.toString(command.orderId()))).isEqualTo(1);
            verify(stock, times(1)).confirm(reservationId);
        } finally {
            reset(target);
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
        }
        verifyNoInteractions(stock);
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
            var a = executor.submit(() -> facade.approve(first));
            var b = executor.submit(() -> facade.approve(second));
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        }
        assertThat(order(first).status()).isEqualTo(OrderStatus.PAID);
        assertThat(replies(first.orderId())).isEqualTo(otherPayment ? 2 : 1);
        if (otherPayment) {
            OrderPayment loser = payment(first).result() == OrderPayment.Result.PAID ? payment(second) : payment(first);
            assertThat(loser.result()).isEqualTo(OrderPayment.Result.REJECTED_ALREADY_PAID);
            assertThat(loser.stockReservationId()).isNull();
        }
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
