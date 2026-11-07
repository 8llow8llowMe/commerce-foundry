package com.commercefoundry.domainlayer.payment;

import static com.commercefoundry.domainlayer.payment.OrderPaymentReplyUnitTest.command;
import static com.commercefoundry.domainlayer.payment.OrderPaymentReplyUnitTest.source;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.*;
import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException;
import com.commercefoundry.domainlayer.payment.application.mapper.PaymentMapperImpl;
import com.commercefoundry.domainlayer.payment.application.port.out.OrderPaymentResultQueryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPolicy;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult.Result;
import com.commercefoundry.domainlayer.payment.application.service.*;
import com.commercefoundry.domainlayer.payment.application.service.processor.*;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.global.messaging.JpaProcessedEventStore;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 Spring 프록시와 회신 Processor의 공통 계약. PostgreSQL 하위 클래스는 동일 시험을 실제 DB에서 실행한다. */
@DataJpaTest
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, PaymentMapperImpl.class, PaymentRepositoryAdapter.class,
    PaymentCancelPersistenceAdapter.class, JpaProcessedEventStore.class, OrderPaymentReplyProcessor.class, PaymentCancelProcessor.class,
    OrderPaymentReplyEventFacade.class, OrderPaymentReplyFailureAdapter.class, OrderPaymentReplyObservation.class, OrderPaymentReplyContract.Beans.class})
@TestPropertySource(properties = {"spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class OrderPaymentReplyContract {
    static final String GROUP = "test.payment.order-payment-result";
    @Autowired OrderPaymentReplyEventFacade facade;
    @Autowired OrderPaymentReplyProcessor processor;
    @Autowired PaymentCancelProcessor cancelProcessor;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean OrderPaymentResultQueryPort query;
    @MockitoBean PaymentCancelPolicy policy;
    @MockitoSpyBean PaymentRepositoryAdapter payments;
    @MockitoSpyBean PaymentCancelPersistenceAdapter cancels;
    @MockitoSpyBean JpaProcessedEventStore inbox;
    @MockitoSpyBean OrderPaymentReplyObservation observation;
    volatile Consumer<Payment> afterRead = p -> { };
    volatile Consumer<Payment> afterSave = p -> { };

    @BeforeEach
    void seed() {
        afterRead = p -> { };
        afterSave = p -> { };
        tx().executeWithoutResult(s -> {
            jdbc.update("delete from payment.payment_cancel");
            jdbc.update("delete from payment.processed_event");
            jdbc.update("delete from payment.payment");
            payments.save(done());
        });
        doAnswer(call -> {
            @SuppressWarnings("unchecked") var found = (java.util.Optional<Payment>) call.callRealMethod();
            found.ifPresent(afterRead);
            return found;
        }).when(payments).findById(PAYMENT_ID);
        doAnswer(call -> {
            Payment saved = (Payment) call.callRealMethod();
            afterSave.accept(saved);
            return saved;
        }).when(payments).save(any());
        when(query.query(PAYMENT_ID)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return source(Result.PAID);
        });
        clearInvocations(payments, cancels);
    }
    TransactionTemplate tx() {
        var tx = new TransactionTemplate(manager);
        tx.setTimeout(15);
        return tx;
    }
    Payment fresh() {
        // 독립 트랜잭션이므로 1차 캐시가 경합 결과를 가리지 않는다.
        return tx().execute(s -> payments.findById(PAYMENT_ID).orElseThrow());
    }
    void result(Result result) {
        when(query.query(PAYMENT_ID)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return source(result);
        });
    }
    long inboxCount() {
        return jdbc.queryForObject("select count(*) from payment.processed_event", Long.class);
    }
    @Test
    void rejectsAmbientTransactionAndDirectRemoteQueryWhileSourceAndHookSeeNoTransaction() {
        assertThatThrownBy(() -> tx().execute(s -> facade.handle(command(Result.PAID)))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx().execute(s -> processor.fetchSource(command(Result.PAID)))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx().execute(s -> { processor.afterRejection(PAYMENT_ID); return null; })).isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(query, policy);
        result(Result.REJECTED_ORDER_CANCELED);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(fresh().orderSyncFailureCode()).isEqualTo("ORDER_CANCELED");
            assertThat(inboxCount()).isEqualTo(1);
            return null;
        }).when(policy).onRejected(PAYMENT_ID);
        assertThat(facade.handle(command(Result.REJECTED_ORDER_CANCELED)).cancelCandidate()).isTrue();
        verify(policy).onRejected(PAYMENT_ID);
    }
    @ParameterizedTest @EnumSource(Result.class)
    void sameDifferentEventsAndCleanupReplayAreBusinessIdempotent(Result result) {
        result(result);
        var event = command(result);
        assertThat(facade.handle(event).changed()).isTrue();
        long version = fresh().version();
        assertThat(facade.handle(event).duplicate()).isTrue();
        assertThat(facade.handle(command(result)).changed()).isFalse();
        assertThat(inbox.deleteProcessedBefore(Clock.systemUTC().instant().plus(Duration.ofDays(1)), 10)).isEqualTo(2);
        assertThat(facade.handle(event).changed()).isFalse();
        assertThat(fresh().version()).isEqualTo(version);
        assertThat(inboxCount()).isEqualTo(1);
        verify(policy, times(result == Result.PAID ? 0 : 1)).onRejected(PAYMENT_ID);
    }
    @Test
    void commitFailureAndBothRollbackDirectionsNeverCallHook() {
        result(Result.REJECTED_ORDER_CANCELED);
        var event = command(Result.REJECTED_ORDER_CANCELED);
        var log = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(OrderPaymentReplyObservation.class);
        log.start();
        logger.addAppender(log);
        try {
            // 선행 inbox를 숨겨 Processor가 payment를 실제 flush한 뒤 같은 키 INSERT를 하게 한다.
            tx().executeWithoutResult(s -> inbox.record(GROUP, event.eventId(), event.kind().eventType(), Clock.systemUTC().instant()));
            doReturn(false).when(inbox).exists(GROUP, event.eventId());
            assertThatThrownBy(() -> facade.handle(event)).isInstanceOfSatisfying(OrderPaymentReplyException.class, e -> assertThat(e.getCause()).isNull());
            assertThat(fresh().orderSyncFailureCode()).isNull();
            assertThat(fresh().version()).isZero();
            assertThat(inboxCount()).isEqualTo(1);
            tx().executeWithoutResult(s -> doAnswer(call -> {
                call.callRealMethod();
                em.flush();
                throw new IllegalStateException(OrderPaymentReplyUnitTest.CANARY);
            }).when(inbox).record(eq(GROUP), any(), anyString(), any()));
            assertThatThrownBy(() -> facade.handle(command(Result.REJECTED_ORDER_CANCELED))).isInstanceOfSatisfying(OrderPaymentReplyException.class,
                e -> assertThat(e.getCause()).isNull());
            assertThat(fresh().orderSyncFailureCode()).isNull();
            assertThat(fresh().version()).isZero();
            assertThat(inboxCount()).isEqualTo(1);
            verifyNoInteractions(policy);
        } finally { logger.detachAppender(log); log.stop(); }
        assertThat(log.list).hasSize(2).allSatisfy(e -> {
            assertThat(e.getThrowableProxy()).isNull();
            assertThat(e.getArgumentArray()).singleElement().isInstanceOf(OrderPaymentReplyException.Reason.class);
            assertThat(e.getFormattedMessage()).doesNotContain(OrderPaymentReplyUnitTest.CANARY, event.eventId().toString(), Long.toString(PAYMENT_ID));
        });
        assertThat(log.list.getFirst().getFormattedMessage()).containsAnyOf("INBOX_DUPLICATE", "STORAGE_CONTRACT");
        assertThat(log.list.getLast().getFormattedMessage()).contains("STORAGE_UNAVAILABLE");
    }
    @Test
    void invalidOriginsReachDomainGuardInsteadOfApprovalTimestampMismatch() {
        when(query.query(PAYMENT_ID)).thenReturn(new OrderPaymentResultQueryResult(PAYMENT_ID, ORDER_ID, MEMBER_ID,
            Result.REJECTED_ORDER_CANCELED, krw(129000), null, null));
        for (Payment invalid : java.util.List.of(ready(), inProgress(), failed(), unknown(), aborted(), lateApproved(), lateApproved().recordCancellation(krw(129000)))) {
            tx().executeWithoutResult(s -> { jdbc.update("delete from payment.payment"); payments.save(invalid); });
            assertThatThrownBy(() -> facade.handle(command(Result.REJECTED_ORDER_CANCELED))).isInstanceOfSatisfying(OrderPaymentReplyException.class,
                e -> assertThat(e.getReason()).isEqualTo(OrderPaymentReplyException.Reason.INVALID_ORIGIN));
            assertThat(fresh().version()).isZero();
        }
        assertThat(inboxCount()).isZero();
        verifyNoInteractions(policy);
    }
    @Test
    void hookFailureAndObservationFailurePreserveCommitAndDoNotRecoverHookOnReplay() {
        result(Result.REJECTED_ORDER_CANCELED);
        doThrow(new IllegalStateException(OrderPaymentReplyUnitTest.CANARY)).when(policy).onRejected(PAYMENT_ID);
        doThrow(new IllegalStateException(OrderPaymentReplyUnitTest.CANARY)).when(observation).record(any());
        var event = command(Result.REJECTED_ORDER_CANCELED);
        assertThat(facade.handle(event).cancelCandidate()).isTrue();
        assertThat(fresh().orderSyncFailureCode()).isEqualTo("ORDER_CANCELED");
        inbox.deleteProcessedBefore(Clock.systemUTC().instant().plusSeconds(1), 10);
        assertThat(facade.handle(event).cancelCandidate()).isFalse();
        verify(policy).onRejected(PAYMENT_ID);
    }
    @Test
    void interruptionAfterCommitBeforeHookLosesFastAttemptEvenAfterCleanup() {
        result(Result.REJECTED_ORDER_CANCELED);
        var event = command(Result.REJECTED_ORDER_CANCELED);
        assertThat(processor.record(event, processor.fetchSource(event)).cancelCandidate()).isTrue();
        // 커밋 뒤 Facade 재개 없이 중단한 창을 재현한다. 새 진입은 훅을 복구하지 않는다.
        assertThat(facade.handle(event).duplicate()).isTrue();
        inbox.deleteProcessedBefore(Clock.systemUTC().instant().plusSeconds(1), 10);
        assertThat(facade.handle(event).cancelCandidate()).isFalse();
        verifyNoInteractions(policy);
    }
    @Test
    void sourceMismatchDoesNotOverwriteSuccessAndMicrosecondComparisonUsesOriginalTotal() {
        var paid = command(Result.PAID);
        var original = source(Result.PAID);
        when(query.query(PAYMENT_ID)).thenReturn(new OrderPaymentResultQueryResult(PAYMENT_ID, ORDER_ID, MEMBER_ID, Result.PAID,
            original.amount(), APPROVED_AT.plusNanos(999), 42L));
        assertThat(facade.handle(paid).changed()).isTrue();
        result(Result.REJECTED_ORDER_CANCELED);
        assertThat(facade.handle(command(Result.REJECTED_ORDER_CANCELED)).changed()).isFalse();
        assertThat(fresh().orderSynced()).isTrue();
        result(Result.PAID);
        assertThatThrownBy(() -> facade.handle(command(Result.REJECTED_ORDER_CANCELED)))
            .isInstanceOfSatisfying(OrderPaymentReplyException.class,
                e -> assertThat(e.getReason()).isEqualTo(OrderPaymentReplyException.Reason.SOURCE_MISMATCH));
        verifyNoInteractions(policy);
    }
    void pendingCancel(long amount) {
        tx().executeWithoutResult(s -> cancels.save(new PaymentCancel(42, PAYMENT_ID, krw(amount), PaymentCancel.Status.PENDING)));
    }
    @TestConfiguration
    static class Beans {
        @Bean SnowflakeIdGenerator snowflakeIdGenerator() { return new SnowflakeIdGenerator(0, 0); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean TopicNames topicNames() { return new TopicNames("test", "payment"); }
    }
}
