package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom.PaymentApprovalSummaryCustomRepositoryImpl;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentApprovalSummaryQueryPort;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAbortReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentGateway;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/** 동일한 집계 계약을 H2와 PostgreSQL 17의 실제 질의로 검증한다. */
@DataJpaTest
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, PaymentApprovalSummaryCustomRepositoryImpl.class,
    PaymentApprovalSummaryQueryAdapter.class})
@TestPropertySource(properties = {"spring.jpa.properties.hibernate.generate_statistics=true", "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"})
abstract class PaymentApprovalSummaryQueryContract {
    static final Instant FROM = Instant.parse("2024-12-15T00:00:00Z");
    static final Instant TO = Instant.parse("2025-01-15T00:00:00Z");
    @Autowired TestEntityManager em;
    @Autowired PaymentApprovalSummaryQueryPort port;

    @Test
    void singleQueryGroupsCurrenciesAndMethodsAndUsesInclusiveExclusiveApprovalTime() {
        save(1, PaymentStatus.DONE, 100, CurrencyCode.KRW, PaymentMethod.CARD, FROM);
        save(2, PaymentStatus.PARTIAL_CANCELED, 200, CurrencyCode.KRW, PaymentMethod.CARD, FROM.plusSeconds(1));
        save(3, PaymentStatus.CANCELED, 300, CurrencyCode.KRW, PaymentMethod.CARD, TO.minusNanos(1000));
        save(4, PaymentStatus.DONE, 50, CurrencyCode.KRW, PaymentMethod.EASY_PAY, FROM);
        save(5, PaymentStatus.DONE, 777, CurrencyCode.USD, PaymentMethod.CARD, FROM);
        save(6, PaymentStatus.DONE, 999, CurrencyCode.KRW, PaymentMethod.CARD, FROM.minusNanos(1000));
        save(7, PaymentStatus.DONE, 999, CurrencyCode.KRW, PaymentMethod.CARD, TO);
        em.flush();
        em.clear();
        var statistics = em.getEntityManager().getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var groups = port.summarize(FROM, TO);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(groups).hasSize(3);
        assertThat(groups.get(0).grossApprovedAmount().currency()).isEqualTo(CurrencyCode.KRW);
        assertThat(groups.get(0).method()).isEqualTo(PaymentMethod.CARD);
        assertThat(groups.get(0).approvalCount()).isEqualTo(3);
        assertThat(groups.get(0).grossApprovedAmount().amount()).isEqualTo(600);
        assertThat(groups.get(1).method()).isEqualTo(PaymentMethod.EASY_PAY);
        assertThat(groups.get(1).grossApprovedAmount().amount()).isEqualTo(50);
        assertThat(groups.get(2).grossApprovedAmount().currency()).isEqualTo(CurrencyCode.USD);
        assertThat(groups.get(2).grossApprovedAmount().amount()).isEqualTo(777);
        // fixtures are inserted now, outside the historical approval window; orderSynced is false for every row.
        assertThat(groups).allSatisfy(group -> assertThat(group.approvalCount()).isPositive());
    }

    @Test
    void excludesEveryUnapprovedStateAndBothLateApprovalOriginsEvenAfterCancellation() {
        for (var status : new PaymentStatus[]{PaymentStatus.READY, PaymentStatus.IN_PROGRESS, PaymentStatus.UNKNOWN,
            PaymentStatus.FAILED, PaymentStatus.ABORTED, PaymentStatus.WAITING_FOR_DEPOSIT}) {
            em.persist(entity(10 + status.ordinal(), status, 999, CurrencyCode.KRW, PaymentMethod.CARD, FROM).build());
        }
        int id = 30;
        for (var status : new PaymentStatus[]{PaymentStatus.LATE_APPROVED, PaymentStatus.CANCELED}) {
            em.persist(entity(id++, status, 999, CurrencyCode.KRW, PaymentMethod.CARD, FROM)
                .failureCode("SYNTHETIC_REJECTED").activeOrderId(null).build());
            em.persist(entity(id++, status, 999, CurrencyCode.KRW, PaymentMethod.CARD, FROM)
                .abortReason(PaymentAbortReason.ADMIN).activeOrderId(null).build());
        }
        em.flush();
        assertThat(port.summarize(FROM, TO)).isEmpty();
    }

    @Test
    void partialAndFullCancellationKeepTheOriginalGrossApproval() {
        save(1, PaymentStatus.DONE, 100, CurrencyCode.KRW, PaymentMethod.CARD, FROM);
        save(2, PaymentStatus.DONE, 200, CurrencyCode.KRW, PaymentMethod.CARD, FROM);
        em.flush();
        var before = port.summarize(FROM, TO);
        ReflectionTestUtils.setField(em.find(PaymentEntity.class, 1L), "status", PaymentStatus.PARTIAL_CANCELED);
        ReflectionTestUtils.setField(em.find(PaymentEntity.class, 2L), "status", PaymentStatus.CANCELED);
        em.flush();
        em.clear();
        assertThat(port.summarize(FROM, TO)).isEqualTo(before);
    }

    @Test
    void longMaxSumIsExactAndAnyOverflowFailsTheWholeResult() {
        save(1, PaymentStatus.DONE, Long.MAX_VALUE - 1, CurrencyCode.KRW, PaymentMethod.CARD, FROM);
        save(2, PaymentStatus.DONE, 1, CurrencyCode.KRW, PaymentMethod.CARD, FROM);
        save(3, PaymentStatus.DONE, 10, CurrencyCode.USD, PaymentMethod.CARD, FROM);
        em.flush();
        assertThat(port.summarize(FROM, TO).getFirst().grossApprovedAmount().amount()).isEqualTo(Long.MAX_VALUE);
        save(4, PaymentStatus.DONE, 1, CurrencyCode.KRW, PaymentMethod.CARD, FROM);
        em.flush();
        assertThatThrownBy(() -> port.summarize(FROM, TO)).isInstanceOfSatisfying(PaymentException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.APPROVAL_SUMMARY_OVERFLOW));
    }

    @Test
    void emptyWindowReturnsNoSyntheticZeroGroup() {
        assertThat(port.summarize(FROM, TO)).isEmpty();
    }

    private void save(long id, PaymentStatus status, long amount, CurrencyCode currency, PaymentMethod method, Instant at) {
        em.persist(entity(id, status, amount, currency, method, at).build());
    }

    private PaymentEntity.PaymentEntityBuilder entity(long id, PaymentStatus status, long amount, CurrencyCode currency, PaymentMethod method, Instant at) {
        boolean approved = status.hasApproval();
        boolean started = status == PaymentStatus.IN_PROGRESS || status == PaymentStatus.UNKNOWN || approved;
        return PaymentEntity.builder().id(id).orderId(id + 100).memberId(42L).pgOrderId("synthetic-" + id)
            .gateway(PaymentGateway.TOSS).totalAmount(amount).totalCurrency(currency).orderName("집계 테스트")
            .status(status).activeOrderId(status.releasesActiveSlot() ? null : id + 100)
            .paymentKey(started ? "synthetic-key-" + id : null).approvalStartedAt(started ? at.minusSeconds(1) : null)
            .method(approved ? method : null).approvedAt(approved ? at : null).orderSynced(false)
            .unknownReason(status == PaymentStatus.UNKNOWN ? PaymentUnknownReason.TIMEOUT : null)
            .failureCode(status == PaymentStatus.FAILED ? "SYNTHETIC_REJECTED" : null)
            .abortReason(status == PaymentStatus.ABORTED ? PaymentAbortReason.ADMIN : null);
    }
}
