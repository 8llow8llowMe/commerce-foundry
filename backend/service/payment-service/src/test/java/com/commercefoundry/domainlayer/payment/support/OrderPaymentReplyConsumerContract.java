package com.commercefoundry.domainlayer.payment.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.payment.adapter.in.event.OrderPaymentReplyEventListener;
import com.commercefoundry.domainlayer.payment.adapter.out.metrics.OrderPaymentReplyMetricsAdapter;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.PaymentOrderSyncMetrics;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.PaymentRepositoryAdapter;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentRepository;
import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand.Kind;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyRetryException;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentResultQueryException;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentResultQueryException.Failure;
import com.commercefoundry.domainlayer.payment.application.mapper.PaymentMapperImpl;
import com.commercefoundry.domainlayer.payment.application.port.in.OrderPaymentReplyEventUseCase;
import com.commercefoundry.domainlayer.payment.application.port.out.OrderPaymentResultQueryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPolicy;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult.Result;
import com.commercefoundry.domainlayer.payment.application.service.OrderPaymentReplyEventFacade;
import com.commercefoundry.domainlayer.payment.application.service.processor.OrderPaymentReplyProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApproval;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.global.messaging.JpaProcessedEventStore;
import com.commercefoundry.messaging.consume.MessagingErrorHandlers;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.messaging.properties.MessagingProperties;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 결제 회신 컨슈머 계약 (#308 PR-C) — 실제 리스너 → 실제 UseCase(Spring 프록시) → 실제 DB 트랜잭션을 H2 · PostgreSQL 에서 같은 시나리오로 돌린다.
 * 외부는 order 원천 조회 포트와 취소 정책 훅만 mock 이다. Kafka 시나리오는 내장 KRaft 브로커와 messaging-core 의 실제 재시도 · DLT 오류 처리기를 쓴다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.properties.hibernate.default_schema=payment", "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
    "commerce.messaging.enabled=false"
})
@Import(OrderPaymentReplyConsumerContract.Config.class)
public abstract class OrderPaymentReplyConsumerContract {

    protected static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private static final Instant APPROVED_AT = Instant.parse("2026-11-02T23:59:58.123456Z");
    private static final String GROUP = "commerce-foundry.test.payment.order-payment-result";
    private static final long MEMBER_ID = 7_000_000_777L;
    private static final AtomicLong IDS = new AtomicLong(9_100_000_000L);
    private static EmbeddedKafkaBroker broker;

    @Autowired protected PaymentRepositoryPort payments;
    @Autowired protected OrderPaymentReplyEventFacade facade;
    @Autowired protected OrderPaymentReplyEventListener listener;
    @Autowired protected OrderPaymentResultQueryPort source;
    @Autowired protected PaymentCancelPolicy cancelPolicy;
    @Autowired protected PaymentOrderSyncMetrics metrics;
    @Autowired protected PaymentRepository paymentRepository;
    @Autowired protected JpaProcessedEventStore inbox;
    @Autowired protected SimpleMeterRegistry meterRegistry;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactions;

    @BeforeAll
    static void startBroker() {
        broker = new EmbeddedKafkaKraftBroker(1, 1).brokerProperties(Map.of("offsets.topic.num.partitions", "1",
            "offsets.topic.replication.factor", "1", "group.initial.rebalance.delay.ms", "0"));
        broker.afterPropertiesSet();
    }

    @AfterAll
    static void stopBroker() {
        broker.destroy();
    }

    @BeforeEach
    void cleanUp() {
        reset(source, cancelPolicy);
        meterRegistry.clear();
        jdbc.update("delete from payment.processed_event");
        jdbc.update("delete from payment.payment");
    }

    @Test
    void paidReplyMarksSyncedWithRemoteQueryOutsideTransactionAndInboxInSameCommit() {
        Payment payment = savedDone();
        OrderPaymentReplyCommand command = paid(payment);
        AtomicInteger remoteOutsideTransaction = new AtomicInteger();
        when(source.query(payment.id())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            remoteOutsideTransaction.incrementAndGet();
            return sourceOf(payment, Result.PAID);
        });

        facade.apply(command);

        Payment synced = reread(payment);
        assertThat(synced.orderSynced()).isTrue();
        assertThat(synced.orderSyncFailureCode()).isNull();
        assertThat(synced.version()).isEqualTo(payment.version() + 1);
        assertThat(inboxCount(command.eventId())).isEqualTo(1);
        assertThat(remoteOutsideTransaction).hasValue(1);
        verify(cancelPolicy, never()).onOrderRejected(anyLong(), anyLong(), anyString());
    }

    @Test
    void rejectedReplyRecordsFailureCodeAndCallsCancelPolicyOnceAcrossRedeliveries() {
        Payment payment = savedDone();
        OrderPaymentReplyCommand command = rejected(payment, "STOCK_UNAVAILABLE");
        when(source.query(payment.id())).thenReturn(sourceOf(payment, Result.REJECTED_STOCK_UNAVAILABLE));

        facade.apply(command);
        facade.apply(command); // 커밋 뒤 ack 전 재시작 — 같은 eventId
        facade.apply(withEvent(command, UUID.randomUUID())); // 같은 회신이 다른 eventId 로 다시 옴(outbox 재발행)

        Payment failed = reread(payment);
        assertThat(failed.orderSynced()).isFalse();
        assertThat(failed.orderSyncFailureCode()).isEqualTo("STOCK_UNAVAILABLE");
        assertThat(failed.version()).isEqualTo(payment.version() + 1);
        assertThat(inboxCount(command.eventId())).isEqualTo(1);
        verify(source, times(2)).query(payment.id()); // 같은 eventId 재수신은 원천 조회 전에 걸러진다
        verify(cancelPolicy, times(1)).onOrderRejected(payment.id(), payment.orderId(), "STOCK_UNAVAILABLE");
    }

    @Test
    void sameEventIdTwiceAppliesOnce() {
        Payment payment = savedDone();
        OrderPaymentReplyCommand command = paid(payment);
        when(source.query(payment.id())).thenReturn(sourceOf(payment, Result.PAID));

        facade.apply(command);
        facade.apply(command);

        assertThat(reread(payment).version()).isEqualTo(payment.version() + 1);
        assertThat(inboxCount(command.eventId())).isEqualTo(1);
        verify(source, times(1)).query(payment.id());
    }

    @Test
    void fullyCanceledPaymentRecordsRejectionWithoutCancelPolicy() {
        Payment canceled = save(savedDone().recordCancellation(krw(129_000)));
        when(source.query(canceled.id())).thenReturn(sourceOf(canceled, Result.REJECTED_ORDER_CANCELED));

        facade.apply(rejected(canceled, "ORDER_CANCELED"));

        Payment after = reread(canceled);
        assertThat(after.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(after.orderSyncFailureCode()).isEqualTo("ORDER_CANCELED");
        verify(cancelPolicy, never()).onOrderRejected(anyLong(), anyLong(), anyString());
    }

    @Test
    void partiallyCanceledPaymentStillRecordsReplies() {
        Payment partial = save(savedDone().recordCancellation(krw(29_000)));
        when(source.query(partial.id())).thenReturn(sourceOf(partial, Result.REJECTED_PAYMENT_NOT_DONE));

        facade.apply(rejected(partial, "PAYMENT_NOT_DONE"));

        assertThat(reread(partial).orderSyncFailureCode()).isEqualTo("PAYMENT_NOT_DONE");
        verify(cancelPolicy, times(1)).onOrderRejected(partial.id(), partial.orderId(), "PAYMENT_NOT_DONE");
    }

    @Test
    void paidReplyForPaymentFullyCanceledFromDoneIsRecordedWithoutPolicy() {
        Payment canceled = save(savedDone().recordCancellation(krw(129_000)));
        when(source.query(canceled.id())).thenReturn(sourceOf(canceled, Result.PAID));

        facade.apply(paid(canceled));

        Payment after = reread(canceled);
        assertThat(after.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(after.orderSynced()).isTrue();
        verify(cancelPolicy, never()).onOrderRejected(anyLong(), anyLong(), anyString());
    }

    @Test
    void replyForCanceledPaymentThatCameFromLateApprovalGoesToDlt() {
        long id = IDS.incrementAndGet();
        Payment canceledLate = save(Payment.ready(id, id + 1, MEMBER_ID, krw(129_000), "주문")
            .startApproval(paymentKey(id), UUID.randomUUID().toString(), NOW.minusSeconds(60))
            .reject("REJECT_CARD_COMPANY", null)
            .recordLateApproval(approval(id))
            .recordCancellation(krw(129_000)));
        assertThat(canceledLate.status()).isEqualTo(PaymentStatus.CANCELED);
        when(source.query(canceledLate.id())).thenReturn(sourceOf(canceledLate, Result.PAID));
        OrderPaymentReplyCommand command = paid(canceledLate);

        assertThatThrownBy(() -> facade.apply(command)).isInstanceOf(NonRetryableEventException.class);
        assertUntouched(canceledLate, command);
    }

    @Test
    void lateRejectionAfterSyncedNeverOverwritesOrCallsPolicy() {
        Payment payment = savedDone();
        when(source.query(payment.id())).thenReturn(sourceOf(payment, Result.PAID));
        facade.apply(paid(payment));
        when(source.query(payment.id())).thenReturn(sourceOf(payment, Result.REJECTED_ORDER_CANCELED));

        OrderPaymentReplyCommand late = rejected(payment, "ORDER_CANCELED");
        facade.apply(late);

        Payment after = reread(payment);
        assertThat(after.orderSynced()).isTrue();
        assertThat(after.orderSyncFailureCode()).isNull();
        assertThat(inboxCount(late.eventId())).isEqualTo(1);
        verify(cancelPolicy, never()).onOrderRejected(anyLong(), anyLong(), anyString());
    }

    @Test
    void sourceMismatchGoesStraightToDltCountedAndLeavesNothing() {
        Payment payment = savedDone();
        OrderPaymentReplyCommand command = paid(payment);
        when(source.query(payment.id())).thenReturn(sourceOf(payment, Result.REJECTED_ORDER_CANCELED));

        // 원천은 최초 결과로 고정돼 다시 조회해도 같다 — 재시도하지 않는다
        assertThatThrownBy(() -> facade.apply(command)).isInstanceOf(NonRetryableEventException.class)
            .hasMessageNotContaining(Long.toString(payment.id()));
        assertThatThrownBy(() -> facade.apply(rejected(payment, "STOCK_UNAVAILABLE")))
            .isInstanceOf(NonRetryableEventException.class); // 다른 거절 사유도 불일치다
        assertUntouched(payment, command);
        assertThat(meterRegistry.get(OrderPaymentReplyMetricsAdapter.SOURCE_MISMATCH).counter().count()).isEqualTo(2.0);
        verify(source, times(2)).query(payment.id());
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void sourceQueryFailureIsRetryableUnlessUnrecoverable(Failure failure) {
        Payment payment = savedDone();
        OrderPaymentReplyCommand command = paid(payment);
        when(source.query(payment.id())).thenThrow(new OrderPaymentResultQueryException(failure));

        boolean unrecoverable = failure == Failure.REJECTED || failure == Failure.CONTRACT_VIOLATION;
        assertThatThrownBy(() -> facade.apply(command))
            .isInstanceOf(unrecoverable ? NonRetryableEventException.class : OrderPaymentReplyRetryException.class);
        assertUntouched(payment, command);
    }

    @Test
    void sourceDifferentFromLocalPaymentGoesToDltWithoutWrites() {
        Payment payment = savedDone();
        OrderPaymentReplyCommand command = paid(payment);
        OrderPaymentResultQueryResult base = sourceOf(payment, Result.PAID);
        List<OrderPaymentResultQueryResult> variants = List.of(
            new OrderPaymentResultQueryResult(base.paymentId(), base.orderId(), MEMBER_ID + 1, Result.PAID, base.amount(), base.approvedAt(), 1L),
            new OrderPaymentResultQueryResult(base.paymentId(), base.orderId(), MEMBER_ID, Result.PAID, krw(128_000), base.approvedAt(), 1L),
            new OrderPaymentResultQueryResult(base.paymentId(), base.orderId(), MEMBER_ID, Result.PAID, base.amount(), APPROVED_AT.plusNanos(1_000), 1L)
        );
        for (OrderPaymentResultQueryResult variant : variants) {
            when(source.query(payment.id())).thenReturn(variant);
            assertThatThrownBy(() -> facade.apply(command)).isInstanceOf(NonRetryableEventException.class);
        }
        // 저장 정밀도(마이크로초) 밑의 차이는 같은 시각이다
        when(source.query(payment.id())).thenReturn(new OrderPaymentResultQueryResult(base.paymentId(), base.orderId(), MEMBER_ID, Result.PAID,
            base.amount(), APPROVED_AT.plusNanos(999), 1L));
        facade.apply(command);
        assertThat(reread(payment).orderSynced()).isTrue();
    }

    @Test
    void missingLocalPaymentAndLateApprovedPaymentGoToDlt() {
        Payment ghost = done(IDS.incrementAndGet());
        when(source.query(ghost.id())).thenReturn(sourceOf(ghost, Result.PAID));
        assertThatThrownBy(() -> facade.apply(paid(ghost))).isInstanceOf(NonRetryableEventException.class);

        long id = IDS.incrementAndGet();
        Payment late = save(Payment.ready(id, id + 1, MEMBER_ID, krw(129_000), "주문")
            .startApproval(paymentKey(id), UUID.randomUUID().toString(), NOW.minusSeconds(60))
            .reject("REJECT_CARD_COMPANY", null)
            .recordLateApproval(approval(id)));
        when(source.query(late.id())).thenReturn(sourceOf(late, Result.REJECTED_ALREADY_PAID));
        OrderPaymentReplyCommand command = rejected(late, "ALREADY_PAID");
        assertThatThrownBy(() -> facade.apply(command)).isInstanceOf(NonRetryableEventException.class);
        assertUntouched(late, command);
    }

    @Test
    void concurrentSameEventCommitsOnceAndCallsPolicyOnce() throws Exception {
        Payment payment = savedDone();
        OrderPaymentReplyCommand command = rejected(payment, "ORDER_NOT_FOUND");
        CountDownLatch bothFetched = new CountDownLatch(2);
        when(source.query(payment.id())).thenAnswer(invocation -> {
            bothFetched.countDown();
            assertThat(bothFetched.await(10, TimeUnit.SECONDS)).isTrue();
            return sourceOf(payment, Result.REJECTED_ORDER_NOT_FOUND);
        });
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        facade.apply(command);
                    } catch (RuntimeException exception) {
                        failures.add(exception);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(failures).hasSizeLessThanOrEqualTo(1).noneMatch(NonRetryableEventException.class::isInstance);
        facade.apply(command); // 진 쪽의 Kafka 재시도 — inbox 로 걸러진다

        assertThat(inboxCount(command.eventId())).isEqualTo(1);
        assertThat(reread(payment).orderSyncFailureCode()).isEqualTo("ORDER_NOT_FOUND");
        verify(cancelPolicy, times(1)).onOrderRejected(payment.id(), payment.orderId(), "ORDER_NOT_FOUND");
    }

    @Test
    void cancelPolicyFailureDoesNotUndoRecord() {
        Payment payment = savedDone();
        OrderPaymentReplyCommand command = rejected(payment, "PAYMENT_MISMATCH");
        when(source.query(payment.id())).thenReturn(sourceOf(payment, Result.REJECTED_PAYMENT_MISMATCH));
        doThrow(new IllegalStateException("POLICY_PRIVATE_SENTINEL")).when(cancelPolicy).onOrderRejected(anyLong(), anyLong(), anyString());

        facade.apply(command);

        assertThat(reread(payment).orderSyncFailureCode()).isEqualTo("PAYMENT_MISMATCH");
        assertThat(inboxCount(command.eventId())).isEqualTo(1);
    }

    @Test
    void gaugesCountAwaitingAndRejectedPaymentsButNotFullyCanceledWithoutReply() {
        savedDone(); // 회신 대기 DONE
        save(savedDone().recordCancellation(krw(29_000))); // 회신 대기 PARTIAL_CANCELED — 돈이 남았다
        save(savedDone().recordCancellation(krw(129_000))); // 전액 취소 — order 가 회신하지 않는 것이 정상이라 세지 않는다
        save(savedDone().recordCancellation(krw(129_000)).markOrderSyncFailed("ORDER_CANCELED")); // 전액 취소된 거절 — 어느 쪽에도 안 센다
        save(savedDone().markOrderSynced());
        Payment rejectedPayment = savedDone();
        when(source.query(rejectedPayment.id())).thenReturn(sourceOf(rejectedPayment, Result.REJECTED_ORDER_CANCELED));
        facade.apply(rejected(rejectedPayment, "ORDER_CANCELED"));

        SimpleMeterRegistry aged = new SimpleMeterRegistry();
        new PaymentOrderSyncMetrics(paymentRepository, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(1)).bindTo(aged);
        assertThat(aged.get(PaymentOrderSyncMetrics.PENDING).gauge().value()).isEqualTo(2.0);
        assertThat(aged.get(PaymentOrderSyncMetrics.REJECTED).gauge().value()).isEqualTo(1.0);

        // 기본 5분 — 방금(2초 전) 승인된 결제는 아직 정상 지연이라 세지 않는다
        SimpleMeterRegistry fresh = new SimpleMeterRegistry();
        metrics.bindTo(fresh);
        assertThat(fresh.get(PaymentOrderSyncMetrics.PENDING).gauge().value()).isZero();
        assertThat(fresh.get(PaymentOrderSyncMetrics.REJECTED).gauge().value()).isEqualTo(1.0);
    }

    @Test
    void inboxRetentionDeletesOnlyBeforeCutoffInBatches() {
        Instant cutoff = NOW.minus(Duration.ofDays(7));
        List<UUID> old = new ArrayList<>();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (int i = 0; i < 3; i++) {
                UUID eventId = UUID.randomUUID();
                old.add(eventId);
                inbox.record(GROUP, eventId, "order-paid", cutoff.minusSeconds(10 - i));
            }
            inbox.record(GROUP, UUID.randomUUID(), "order-paid", cutoff); // 경계 — 지우지 않는다
            inbox.record(GROUP, UUID.randomUUID(), "order-paid", cutoff.plusSeconds(1));
        });

        assertThat(inbox.deleteProcessedBefore(cutoff, 2)).isEqualTo(2); // batch 상한
        assertThat(inbox.exists(GROUP, old.get(0))).isFalse(); // 오래된 것부터
        assertThat(inbox.exists(GROUP, old.get(1))).isFalse();
        assertThat(inbox.exists(GROUP, old.get(2))).isTrue();
        assertThat(inbox.deleteProcessedBefore(cutoff, 2)).isEqualTo(1);
        assertThat(inbox.deleteProcessedBefore(cutoff, 2)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment.processed_event", Long.class)).isEqualTo(2);
    }

    @Test
    void kafkaDuplicateDeliveriesThroughRealListenerApplyOnce() throws Exception {
        Payment payment = savedDone();
        OrderPaymentReplyCommand command = paid(payment);
        when(source.query(payment.id())).thenReturn(sourceOf(payment, Result.PAID));
        try (KafkaHarness kafka = new KafkaHarness()) {
            kafka.send(command);
            kafka.send(command);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(kafka.handled).hasValue(2));
        }
        assertThat(reread(payment).orderSynced()).isTrue();
        assertThat(inboxCount(command.eventId())).isEqualTo(1);
        verify(source, times(1)).query(payment.id());
    }

    @Test
    void kafkaTransientSourceFailureIsRetriedThenApplied() throws Exception {
        Payment payment = savedDone();
        OrderPaymentReplyCommand command = rejected(payment, "ORDER_CANCELED");
        when(source.query(payment.id()))
            .thenThrow(new OrderPaymentResultQueryException(Failure.UNAVAILABLE))
            .thenReturn(sourceOf(payment, Result.REJECTED_ORDER_CANCELED));
        try (KafkaHarness kafka = new KafkaHarness()) {
            kafka.send(command);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(kafka.handled).hasValue(1));
            assertThat(kafka.deadLetters(Duration.ofSeconds(1))).isEmpty();
        }
        assertThat(reread(payment).orderSyncFailureCode()).isEqualTo("ORDER_CANCELED");
        verify(source, times(2)).query(payment.id());
        verify(cancelPolicy, times(1)).onOrderRejected(payment.id(), payment.orderId(), "ORDER_CANCELED");
    }

    @Test
    void kafkaExhaustedRetriesAndUnrecoverableFailuresGoToDlt() throws Exception {
        Payment unavailable = savedDone();
        when(source.query(unavailable.id())).thenThrow(new OrderPaymentResultQueryException(Failure.UNAVAILABLE)); // 계속 장애 — 재시도 소진
        Payment mismatched = savedDone();
        when(source.query(mismatched.id())).thenReturn(sourceOf(mismatched, Result.REJECTED_ORDER_CANCELED)); // 이벤트는 paid — 계속 불일치
        Payment ghost = done(IDS.incrementAndGet()); // 로컬에 없는 결제 — 회복 불가
        when(source.query(ghost.id())).thenReturn(sourceOf(ghost, Result.PAID));
        try (KafkaHarness kafka = new KafkaHarness()) {
            kafka.send(paid(unavailable));
            kafka.send(paid(mismatched));
            kafka.send(paid(ghost));
            List<ConsumerRecord<String, String>> dead = kafka.awaitDeadLetters(3);
            assertThat(dead).extracting(ConsumerRecord::key).containsExactlyInAnyOrder(
                Long.toString(unavailable.orderId()), Long.toString(mismatched.orderId()), Long.toString(ghost.orderId()));
        }
        verify(source, times(3)).query(unavailable.id()); // 시도 3회(테스트 재시도 설정) 뒤 DLT
        verify(source, times(1)).query(mismatched.id()); // 원천 불일치는 재시도 없이 DLT
        verify(source, times(1)).query(ghost.id()); // 재시도 없이 DLT
        assertThat(reread(unavailable).orderSynced()).isFalse();
        assertThat(reread(mismatched).orderSynced()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from payment.processed_event", Long.class)).isZero();
    }

    // ── 도우미 ───────────────────────────────────────────────

    /** 회신 토픽 두 개와 DLT 를 테스트마다 새로 만들고, 실제 리스너 빈을 messaging-core 오류 처리기(재시도 3회 · 짧은 백오프)로 감싸 돌린다. */
    private final class KafkaHarness implements AutoCloseable {

        private final String namespace = "commerce-foundry.test-" + UUID.randomUUID();
        private final String paidTopic = namespace + ".order.order-paid";
        private final String rejectedTopic = namespace + ".order.order-payment-rejected";
        private final DefaultKafkaProducerFactory<String, String> producerFactory = new DefaultKafkaProducerFactory<>(
            Map.of("bootstrap.servers", broker.getBrokersAsString()), new StringSerializer(), new StringSerializer());
        private final KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        private final EventEnvelopeCodec codec = new EventEnvelopeCodec(65536);
        private final AtomicInteger handled = new AtomicInteger();
        private final ConcurrentMessageListenerContainer<String, String> container;

        KafkaHarness() throws Exception {
            try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", broker.getBrokersAsString()))) {
                admin.createTopics(List.of(new NewTopic(paidTopic, 1, (short) 1), new NewTopic(paidTopic + ".dlt", 1, (short) 1),
                    new NewTopic(rejectedTopic, 1, (short) 1), new NewTopic(rejectedTopic + ".dlt", 1, (short) 1))).all().get();
            }
            var consumers = new DefaultKafkaConsumerFactory<String, String>(Map.of("bootstrap.servers", broker.getBrokersAsString(),
                "group.id", namespace + ".payment.order-payment-result", "auto.offset.reset", "earliest", "enable.auto.commit", false,
                "max.poll.records", 1), new StringDeserializer(), new StringDeserializer());
            var properties = new ContainerProperties(paidTopic, rejectedTopic);
            properties.setAckMode(ContainerProperties.AckMode.RECORD);
            properties.setMessageListener((MessageListener<String, String>) record -> {
                listener.onOrderPaymentReply(record); // 실제 listener bean → 실제 UseCase Spring 프록시 → 실제 DB
                handled.incrementAndGet();
            });
            container = new ConcurrentMessageListenerContainer<>(consumers, properties);
            container.setCommonErrorHandler(MessagingErrorHandlers.create(template,
                new MessagingProperties.Retry(3, Duration.ofMillis(50), 2, Duration.ofMillis(100)), null));
            container.start();
        }

        void send(OrderPaymentReplyCommand command) throws Exception {
            String orderId = Long.toString(command.orderId());
            Object message = command.kind() == Kind.PAID
                ? new OrderPaymentReplyEventListener.OrderPaidMessage(orderId, Long.toString(command.paymentId()), "77", NOW)
                : new OrderPaymentReplyEventListener.OrderPaymentRejectedMessage(orderId, Long.toString(command.paymentId()), command.rejectionReason());
            EventEnvelope envelope = new EventEnvelope(command.eventId(), command.kind().eventType(), 1, NOW, "order", "order", orderId, codec.toTree(message));
            template.send(command.kind() == Kind.PAID ? paidTopic : rejectedTopic, orderId, codec.encode(envelope)).get();
        }

        List<ConsumerRecord<String, String>> awaitDeadLetters(int expected) {
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                records.addAll(deadLetters(Duration.ofMillis(500)));
                assertThat(records).hasSize(expected);
            });
            return records;
        }

        private KafkaConsumer<String, String> dltConsumer;

        List<ConsumerRecord<String, String>> deadLetters(Duration poll) {
            if (dltConsumer == null) {
                dltConsumer = new KafkaConsumer<>(Map.of("bootstrap.servers", broker.getBrokersAsString(), "group.id", namespace + ".dlt-reader",
                    "auto.offset.reset", "earliest"), new StringDeserializer(), new StringDeserializer());
                dltConsumer.subscribe(List.of(paidTopic + ".dlt", rejectedTopic + ".dlt"));
            }
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            dltConsumer.poll(poll).forEach(records::add);
            return records;
        }

        @Override
        public void close() {
            container.stop();
            if (dltConsumer != null) {
                dltConsumer.close();
            }
            producerFactory.destroy();
        }
    }

    private void assertUntouched(Payment payment, OrderPaymentReplyCommand command) {
        Payment after = reread(payment);
        assertThat(after.version()).isEqualTo(payment.version());
        assertThat(after.orderSynced()).isFalse();
        assertThat(after.orderSyncFailureCode()).isNull();
        assertThat(inboxCount(command.eventId())).isZero();
        verify(cancelPolicy, never()).onOrderRejected(anyLong(), anyLong(), anyString());
    }

    private Payment savedDone() {
        return save(done(IDS.incrementAndGet()));
    }

    private static Payment done(long id) {
        return Payment.ready(id, id + 1, MEMBER_ID, krw(129_000), "생활한복 저고리")
            .startApproval(paymentKey(id), UUID.randomUUID().toString(), NOW.minusSeconds(60))
            .approve(approval(id));
    }

    private static String paymentKey(long id) {
        return "tgen_contract_" + id;
    }

    private static PaymentApproval approval(long id) {
        return new PaymentApproval(paymentKey(id), PaymentMethod.CARD, null, APPROVED_AT, "DONE", krw(129_000));
    }

    private static Money krw(long amount) {
        return Money.of(amount, CurrencyCode.KRW);
    }

    private Payment save(Payment payment) {
        return new TransactionTemplate(transactions).execute(status -> payments.save(payment));
    }

    private Payment reread(Payment payment) {
        return new TransactionTemplate(transactions).execute(status -> payments.findById(payment.id()).orElseThrow());
    }

    private long inboxCount(UUID eventId) {
        return jdbc.queryForObject("select count(*) from payment.processed_event where consumer_group = ? and event_id = ?", Long.class, GROUP, eventId);
    }

    private static OrderPaymentResultQueryResult sourceOf(Payment payment, Result result) {
        return new OrderPaymentResultQueryResult(payment.id(), payment.orderId(), payment.memberId(), result, payment.amount(), payment.approvedAt(),
            result == Result.PAID ? 77L : null);
    }

    private static OrderPaymentReplyCommand paid(Payment payment) {
        return new OrderPaymentReplyCommand(UUID.randomUUID(), Kind.PAID, payment.id(), payment.orderId(), null);
    }

    private static OrderPaymentReplyCommand rejected(Payment payment, String reason) {
        return new OrderPaymentReplyCommand(UUID.randomUUID(), Kind.REJECTED, payment.id(), payment.orderId(), reason);
    }

    private static OrderPaymentReplyCommand withEvent(OrderPaymentReplyCommand command, UUID eventId) {
        return new OrderPaymentReplyCommand(eventId, command.kind(), command.paymentId(), command.orderId(), command.rejectionReason());
    }

    @TestConfiguration
    @Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, PaymentRepositoryAdapter.class, PaymentMapperImpl.class,
        JpaProcessedEventStore.class, OrderPaymentReplyProcessor.class, OrderPaymentReplyEventFacade.class, PaymentOrderSyncMetrics.class,
        OrderPaymentReplyMetricsAdapter.class})
    public static class Config {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean SimpleMeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
        @Bean SnowflakeIdGenerator snowflakeIdGenerator() { return new SnowflakeIdGenerator(4, 4); }
        @Bean TopicNames topicNames() { return new TopicNames("commerce-foundry.test", "payment"); }
        @Bean OrderPaymentResultQueryPort orderPaymentResultQueryPort() { return mock(OrderPaymentResultQueryPort.class); }
        @Bean PaymentCancelPolicy paymentCancelPolicy() { return mock(PaymentCancelPolicy.class); }
        @Bean OrderPaymentReplyEventListener orderPaymentReplyEventListener(OrderPaymentReplyEventUseCase useCase) {
            // 업무 UseCase 는 실제 Spring 프록시다. 외부 order 원천 조회 · 취소 정책 훅만 mock 이다
            return new OrderPaymentReplyEventListener(new EventEnvelopeCodec(65536), useCase);
        }
    }
}
