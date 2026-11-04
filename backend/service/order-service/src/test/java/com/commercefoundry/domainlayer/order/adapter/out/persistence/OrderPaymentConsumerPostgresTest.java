package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.support.OrderPaymentConsumerContract;
import com.commercefoundry.domainlayer.order.application.model.StockConfirmation;
import com.commercefoundry.domainlayer.order.application.model.VerifiedPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import com.commercefoundry.messaging.support.MessagingKafkaContainers;
import com.commercefoundry.domainlayer.order.adapter.in.event.PaymentApprovedEventListener;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import static org.awaitility.Awaitility.await;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"})
@Import(OrderPaymentConsumerH2Test.Config.class)
class OrderPaymentConsumerPostgresTest extends OrderPaymentConsumerContract {
    private static final AtomicLong IDS = new AtomicLong(70_000_000);

    @Test
    void kafkaRedeliversAfterActualNowaitExhaustionAndCommitsPaidExactlyOnce() throws Exception {
        var command = arrange(OrderStatus.PENDING_PAYMENT);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch exhausted = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger deliveries = new AtomicInteger();
        String topic = "commerce-foundry.test.payment.nowait-" + UUID.randomUUID();
        var codec = new EventEnvelopeCodec(65536);
        var envelope = new EventEnvelope(command.eventId(), "payment-approved", 1, NOW, "payment", "payment", Long.toString(command.orderId()),
            codec.toTree(new PaymentApprovedEventListener.PaymentApprovedMessage(Long.toString(command.paymentId()), Long.toString(command.orderId()),
                Long.toString(command.memberId()), command.amount().amount(), command.amount().currency().name(), NOW, "CARD")));
        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of("bootstrap.servers", bootstrapServers()),
            new StringSerializer(), new StringSerializer());
        var kafka = new KafkaTemplate<>(producerFactory);
        var consumers = new DefaultKafkaConsumerFactory<String, String>(Map.of("bootstrap.servers", bootstrapServers(),
            "group.id", "commerce-foundry.test.order.payment-approved", "auto.offset.reset", "earliest", "enable.auto.commit", false, "max.poll.records", 1),
            new StringDeserializer(), new StringDeserializer());
        ConcurrentMessageListenerContainer<String, String> container = null;
        try (var executor = Executors.newSingleThreadExecutor(); var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers()))) {
            var lockHolder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                orders.findByIdAndMemberIdForUpdate(command.orderId(), command.memberId()).orElseThrow();
                holding.countDown();
                awaitLatch(releaseLock);
            }));
            try {
                assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1), new NewTopic(topic + ".dlt", 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
                container = startConsumer(topic, consumers, kafka, deliveries, failure -> {
                    assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage("order payment conflict retry exhausted");
                    failures.incrementAndGet();
                    exhausted.countDown();
                    // ErrorHandler에 원래 예외를 다시 던지기 전에 부모 트랜잭션 커밋을 보장한다. 실제 재전달은 Kafka ErrorHandler가 한다.
                    awaitLatch(committed);
                });
                kafka.send(topic, envelope.aggregateId(), codec.encode(envelope)).get(10, TimeUnit.SECONDS);
                assertThat(exhausted.await(30, TimeUnit.SECONDS)).isTrue();
                assertThat(payments.findByPaymentId(command.paymentId())).isEmpty();
                assertThat(inbox.exists("commerce-foundry.test.order.payment-approved", command.eventId())).isFalse();
                assertThat(jdbc.queryForObject("select count(*) from orders.outbox where aggregate_id=?",
                    Long.class, Long.toString(command.orderId()))).isZero();
            } finally {
                releaseLock.countDown();
                try {
                    lockHolder.get(20, TimeUnit.SECONDS);
                } finally {
                    committed.countDown();
                }
            }
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(deliveries).hasValue(1));
            assertThat(failures).hasValue(1);
            assertSingleReflection(command, codec);
        } finally {
            releaseLock.countDown();
            committed.countDown();
            if (container != null) {
                container.stop();
            }
            producerFactory.destroy();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(45, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parentNowaitSerializesPaidAndIntentDecisionsAcrossTwoTransactions(boolean paidFirst) throws Exception {
        var command = arrange(OrderStatus.PENDING_PAYMENT);
        long reservationId = jdbc.queryForObject("select stock_reservation_id from orders.orders where id=?", Long.class, command.orderId());
        var verified = new VerifiedPayment(command.paymentId(), command.orderId(), command.memberId(), command.amount(), NOW, true, false);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var winner = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                var before = orders.findByIdAndMemberIdForUpdate(command.orderId(), command.memberId()).orElseThrow();
                if (paidFirst) {
                    var paid = before.markPaid(reservationId);
                    orders.saveStatusChange(paid,
                        OrderStatusHistory.changedByService(IDS.incrementAndGet(), before, paid, OrderStatusHistory.PAYMENT_APPROVED));
                    payments.insert(OrderPayment.paid(IDS.incrementAndGet(), paid, IDS.incrementAndGet(), command.amount(), NOW, UUID.randomUUID()));
                } else {
                    // C2b 대비 영속 fixture: C1이 PENDING 주문에 의도를 만드는 실제 경로를 뜻하지 않는다.
                    payments.insert(OrderPayment.canceledAfterConfirmation(IDS.incrementAndGet(), command.orderId(), IDS.incrementAndGet(),
                        command.memberId(), command.amount(), NOW, reservationId, UUID.randomUUID(), IDS.incrementAndGet()));
                }
                holding.countDown();
                try {
                    assertThat(commit.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> processor.complete(command, verified, StockConfirmation.confirmed(reservationId)))
                    .isInstanceOfAny(PessimisticLockingFailureException.class, jakarta.persistence.PessimisticLockException.class,
                        jakarta.persistence.LockTimeoutException.class);
                assertThatThrownBy(() -> facade.approve(command))
                    .isInstanceOf(IllegalStateException.class).hasMessage("order payment conflict retry exhausted");
                assertThat(payments.findByPaymentId(command.paymentId())).isEmpty();
                assertThat(inbox.exists("commerce-foundry.test.order.payment-approved", command.eventId())).isFalse();
            } finally {
                commit.countDown();
            }
            winner.get(20, TimeUnit.SECONDS);
        }
        if (paidFirst) {
            jdbc.update("update orders.orders set status='CANCELED',version=version+1 where id=?", command.orderId());
        }

        // 승자의 커밋 뒤 실패한 동일 승인을 한 번 재전달한다. Kafka 자체 재전달이 아니라 명시적 재전달 모사다.
        facade.approve(command);

        var loser = payments.findByPaymentId(command.paymentId()).orElseThrow();
        assertThat(loser.result()).isEqualTo(paidFirst ? OrderPayment.Result.REJECTED_ORDER_CANCELED : OrderPayment.Result.REJECTED_STOCK_UNAVAILABLE);
        assertThat(loser.restorationClaimId()).isNull();
        assertThat(payments.findPaidByOrderId(command.orderId()).isPresent()).isEqualTo(paidFirst);
        assertThat(payments.existsRestorationIntent(command.orderId(), reservationId)).isEqualTo(!paidFirst);
        assertThat(inbox.exists("commerce-foundry.test.order.payment-approved", command.eventId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from orders.order_payment where payment_id=?", Long.class, command.paymentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from orders.outbox where aggregate_id=? and event_type='order-payment-rejected'",
            Long.class, Long.toString(command.orderId()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from orders.order_status_history where order_id=? and to_status='PAID'",
            Long.class, command.orderId())).isEqualTo(paidFirst ? 1 : 0);
    }
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");

    @Container static final KafkaContainer KAFKA = MessagingKafkaContainers.kafka();

    @Override protected String bootstrapServers() { return KAFKA.getBootstrapServers(); }
}
