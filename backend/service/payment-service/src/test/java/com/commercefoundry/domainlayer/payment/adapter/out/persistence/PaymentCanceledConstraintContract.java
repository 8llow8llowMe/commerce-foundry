package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.domainlayer.payment.application.mapper.PaymentMapperImpl;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentCancelProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 Processor의 prepare/complete를 각각 커밋하고, 영속성 컨텍스트를 비운 뒤 상태와 원장을 다시 읽는다. */
@DataJpaTest
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, PaymentMapperImpl.class, PaymentRepositoryAdapter.class,
    PaymentCancelPersistenceAdapter.class, PaymentCancelProcessor.class, PaymentCanceledConstraintContract.FixedClock.class})
@TestPropertySource(properties = {"spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class PaymentCanceledConstraintContract {
    @Autowired PaymentRepositoryPort payments;
    @Autowired PaymentCancelPersistencePort cancellations;
    @Autowired PaymentCancelProcessor processor;
    @Autowired PaymentMapperImpl mapper;
    @Autowired TestEntityManager em;
    @Autowired PlatformTransactionManager transactions;

    enum Origin { NORMAL, FAILED, ABORTED }
    enum InvalidRow { FAILED_WITHOUT_CODE, DONE_WITH_CODE, PARTIAL_WITH_CODE, MESSAGE_WITHOUT_CODE, APPROVAL_MISSING }

    @ParameterizedTest
    @EnumSource(Origin.class)
    void fullCancellationCommitsOriginalApprovalAndOriginWithSucceededLedger(Origin origin) {
        Payment initial = switch (origin) {
            case NORMAL -> done();
            case FAILED -> lateApproved();
            case ABORTED -> aborted().recordLateApproval(approval(krw(129_000)));
        };
        save(initial);
        processor.prepare(PAYMENT_ID, 42, initial.amount());
        processor.complete(PAYMENT_ID, 42);
        Payment canceled = reread();
        assertThat(canceled.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(canceled.amount()).isEqualTo(initial.amount());
        assertThat(canceled.approvedAt()).isEqualTo(initial.approvedAt());
        assertThat(canceled.failureCode()).isEqualTo(initial.failureCode());
        assertThat(canceled.failureMessage()).isEqualTo(initial.failureMessage());
        assertThat(canceled.abortReason()).isEqualTo(initial.abortReason());
        assertThat(canceled.activeOrderId()).isEqualTo(origin == Origin.NORMAL ? ORDER_ID : null);
        assertSucceeded(42, initial.amount().amount());

        // 중복 complete는 PG를 호출하지 않고 같은 원장과 버전을 반환한다.
        processor.complete(PAYMENT_ID, 42);
        assertThat(reread()).isEqualTo(canceled);
        assertSucceeded(42, initial.amount().amount());
    }

    @Test
    void partialThenRemainingFullCancellationPreservesApprovalAndBothLedgerRows() {
        save(done());
        processor.prepare(PAYMENT_ID, 41, krw(29_000));
        processor.complete(PAYMENT_ID, 41);
        assertThat(reread().status()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
        assertThat(reread().amount()).isEqualTo(krw(129_000));
        processor.prepare(PAYMENT_ID, 42, krw(100_000));
        processor.complete(PAYMENT_ID, 42);
        assertThat(reread().status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(reread().amount()).isEqualTo(krw(129_000));
        assertThat(reread().approvedAt()).isEqualTo(APPROVED_AT);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.clear();
            assertThat(cancellations.findByPaymentId(PAYMENT_ID)).hasSize(2).allSatisfy(cancel ->
                assertThat(cancel.status()).isEqualTo(PaymentCancel.Status.SUCCEEDED));
        });
    }

    @ParameterizedTest
    @EnumSource(InvalidRow.class)
    void otherFailureAndApprovalFieldViolationsAreStillRejected(InvalidRow invalid) {
        Payment initial = switch (invalid) {
            case FAILED_WITHOUT_CODE -> failed();
            case PARTIAL_WITH_CODE -> done().recordCancellation(krw(1));
            case APPROVAL_MISSING -> done().recordCancellation(krw(129_000));
            default -> done();
        };
        PaymentEntity entity = mapper.toEntity(initial);
        switch (invalid) {
            case FAILED_WITHOUT_CODE -> {
                ReflectionTestUtils.setField(entity, "failureCode", null);
                ReflectionTestUtils.setField(entity, "failureMessage", null);
            }
            case DONE_WITH_CODE, PARTIAL_WITH_CODE -> ReflectionTestUtils.setField(entity, "failureCode", "SYNTHETIC_REJECTION");
            case MESSAGE_WITHOUT_CODE -> ReflectionTestUtils.setField(entity, "failureMessage", "synthetic message");
            case APPROVAL_MISSING -> ReflectionTestUtils.setField(entity, "approvedAt", null);
        }
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.persist(entity);
            em.flush();
        })).hasRootCauseInstanceOf(SQLException.class);
    }

    void save(Payment payment) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> payments.save(payment));
    }

    Payment reread() {
        return new TransactionTemplate(transactions).execute(status -> {
            em.flush();
            em.clear();
            return payments.findById(PAYMENT_ID).orElseThrow();
        });
    }

    void assertSucceeded(long claimId, long amount) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.clear();
            assertThat(cancellations.findByPaymentId(PAYMENT_ID)).singleElement().satisfies(cancel -> {
                assertThat(cancel.claimId()).isEqualTo(claimId);
                assertThat(cancel.status()).isEqualTo(PaymentCancel.Status.SUCCEEDED);
                assertThat(cancel.amount()).isEqualTo(krw(amount));
            });
        });
    }

    @AfterEach
    void cleanRows() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.getEntityManager().createQuery("delete from PaymentCancelEntity").executeUpdate();
            em.getEntityManager().createQuery("delete from PaymentEntity").executeUpdate();
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean Clock clock() {
            return Clock.fixed(APPROVED_AT, ZoneOffset.UTC);
        }
    }
}
