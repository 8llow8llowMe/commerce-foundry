package com.commercefoundry;

import static com.commercefoundry.domainlayer.payment.support.PaymentAdminQueryFixtures.ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentAdminQueryFixtures.entity;
import static com.commercefoundry.domainlayer.payment.support.PaymentAdminQueryFixtures.externalRefundCancellations;
import static com.commercefoundry.domainlayer.payment.support.PaymentAdminQueryFixtures.externalRefundPayments;
import static com.commercefoundry.domainlayer.payment.support.PaymentAdminQueryFixtures.refundHistoryPayments;
import static com.commercefoundry.domainlayer.payment.support.PaymentAdminQueryFixtures.refundHistoryCancellations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.payment.adapter.in.admin.dto.item.PaymentAdminItem;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.PaymentAdminQueryAdapter;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.domainlayer.payment.application.command.PaymentAdminSearchCommand;
import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminStatusFilter;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentAdminUseCase;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:payment-admin-query;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS payment",
    "spring.jpa.properties.hibernate.generate_statistics=true"
})
@ActiveProfiles("test")
@Transactional
class PaymentAdminQueryIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private EntityManagerFactory emf;
    @Autowired private PaymentAdminUseCase useCase;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private PaymentAdminQueryAdapter queryAdapter;

    @Test
    void actualQueryCombinesFiltersAndKeepsTotalsForEmptyLaterPage() {
        seed();
        var all = useCase.search(command(null, null, null, 0, 2));
        assertThat(all.contents()).extracting(item -> item.paymentId()).containsExactly("" + (ID + 6), "" + (ID + 5));
        assertThat(all.totalElements()).isEqualTo(6);
        assertThat(all.totalPages()).isEqualTo(3);
        var unknown = useCase.search(command(PaymentAdminStatusFilter.UNKNOWN, null, false, 0, 1));
        assertThat(unknown.contents()).singleElement().satisfies(item -> {
            assertThat(item.paymentId()).isEqualTo("" + (ID + 2));
            assertThat(item.unknownReason().code()).isEqualTo("TIMEOUT");
            assertThat(item.method()).isNull();
        });
        assertThat(unknown.totalElements()).isEqualTo(2);
        var beyond = useCase.search(command(PaymentAdminStatusFilter.UNKNOWN, null, false, 4, 1));
        assertThat(beyond.contents()).isEmpty();
        assertThat(beyond.totalElements()).isEqualTo(2);
        assertThat(useCase.search(command(null, 101L, null, 0, 20)).contents()).singleElement();
        assertThat(useCase.search(command(PaymentAdminStatusFilter.DONE, 101L, null, 0, 20)).contents()).isEmpty();
        assertThat(useCase.search(command(null, 999L, null, 0, 20)).totalElements()).isZero();
        assertThat(useCase.search(command(null, null, true, 0, 20)).contents()).singleElement().satisfies(item -> {
            assertThat(item.paymentId()).isEqualTo("" + (ID + 3));
            assertThat(item.method().code()).isEqualTo("CARD");
            assertThat(item.unknownReason().code()).isEqualTo("TIMEOUT"); // 해결 후 이력도 보존
        });
        var rejected = useCase.search(command(PaymentAdminStatusFilter.DONE, null, false, 0, 20));
        assertThat(rejected.contents()).singleElement().satisfies(item -> assertThat(item.orderSyncFailureCode()).isEqualTo("ORDER_CANCELED"));
    }

    @Test
    void readsListCountAndOneExternalRefundAggregateWithoutChangingVersions() {
        seed();
        Long version = em.find(PaymentEntity.class, ID + 1).getVersion();
        em.clear();
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        var page = useCase.search(command(null, null, null, 0, 20));
        assertThat(page.contents()).hasSize(6).allSatisfy(item -> assertThat(item.externalRefund()).isNull());
        // 목록 · count · 페이지 단위 외부 환불 집계 1회 — 항목 수와 무관하다(N+1 없음)
        assertThat(stats.getPrepareStatementCount()).isEqualTo(3);
        stats.clear();
        assertThat(useCase.search(command(PaymentAdminStatusFilter.UNKNOWN, null, false, 4, 1)).contents()).isEmpty();
        assertThat(stats.getPrepareStatementCount()).isEqualTo(2); // 빈 페이지는 집계 질의를 하지 않는다
        em.flush();
        em.clear();
        assertThat(em.find(PaymentEntity.class, ID + 1).getVersion()).isEqualTo(version);
    }

    @Test
    void refundFailedMeansAnyRejectedHistoryAndKeepsActualStatusAfterLaterSuccess() {
        refundHistoryPayments().forEach(em::persist);
        refundHistoryCancellations().forEach(em::persist);
        em.flush();
        Long version = em.find(PaymentEntity.class, ID + 2).getVersion();
        em.clear();
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        var history = useCase.search(command(PaymentAdminStatusFilter.REFUND_FAILED, null, false, 0, 20));
        assertThat(history.contents()).extracting(item -> item.paymentId()).containsExactly("" + (ID + 2), "" + (ID + 1));
        assertThat(history.totalElements()).isEqualTo(2);
        assertThat(history.contents().getFirst().status().code()).isEqualTo("CANCELED");
        assertThat(stats.getPrepareStatementCount()).isEqualTo(3);
        em.flush();
        assertThat(stats.getEntityUpdateCount()).isZero();
        assertThat(stats.getEntityInsertCount()).isZero();
        assertThat(stats.getEntityDeleteCount()).isZero();
        em.clear();
        assertThat(em.find(PaymentEntity.class, ID + 2).getVersion()).isEqualTo(version);

        var one = useCase.search(command(PaymentAdminStatusFilter.REFUND_FAILED, 101L, false, 0, 20));
        assertThat(one.contents()).singleElement().satisfies(item -> assertThat(item.status().code()).isEqualTo("DONE"));
        assertThat(one.totalElements()).isEqualTo(1); // two REJECTED rows still mean one payment
        assertThat(useCase.search(command(PaymentAdminStatusFilter.REFUND_FAILED, null, null, 0, 20)).totalElements()).isEqualTo(3);
        assertThat(useCase.search(command(PaymentAdminStatusFilter.REFUND_FAILED, null, true, 0, 20)).contents()).singleElement();
        assertThat(useCase.search(command(PaymentAdminStatusFilter.REFUND_FAILED, 103L, false, 0, 20)).contents()).isEmpty();
        var first = useCase.search(command(PaymentAdminStatusFilter.REFUND_FAILED, null, false, 0, 1));
        assertThat(first.contents()).singleElement().satisfies(item -> assertThat(item.paymentId()).isEqualTo("" + (ID + 2)));
        var later = useCase.search(command(PaymentAdminStatusFilter.REFUND_FAILED, null, false, 4, 1));
        assertThat(later.contents()).isEmpty();
        assertThat(later.totalElements()).isEqualTo(2);
        assertThat(useCase.search(command(PaymentAdminStatusFilter.CANCELED, null, false, 0, 20)).contents()).singleElement();
        assertThat(useCase.search(command(PaymentAdminStatusFilter.FAILED, null, false, 0, 20)).contents()).singleElement()
            .satisfies(item -> assertThat(item.paymentId()).isEqualTo("" + (ID + 5)));
    }

    @Test
    void externalRefundedFilterAndPageTotalsFollowExternalRefundRowsOnly() {
        externalRefundPayments().forEach(em::persist);
        externalRefundCancellations().forEach(em::persist);
        em.flush();
        em.clear();
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        var external = useCase.search(command(PaymentAdminStatusFilter.EXTERNAL_REFUNDED, null, null, 0, 20));
        assertThat(external.contents()).extracting(item -> item.paymentId())
            .containsExactly("" + (ID + 4), "" + (ID + 3), "" + (ID + 2), "" + (ID + 1));
        assertThat(external.totalElements()).isEqualTo(4);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(3);
        assertThat(stats.getEntityUpdateCount()).isZero();
        assertThat(external.contents()).satisfiesExactly(
            item -> assertExternalRefund(item, 0, 50),
            item -> {
                assertExternalRefund(item, 400, 0); // SUCCEEDED 100 은 외부 환불이 아니다
                assertThat(item.status().code()).isEqualTo("PARTIAL_CANCELED");
            },
            item -> {
                assertExternalRefund(item, 0, 700);
                assertThat(item.status().code()).isEqualTo("DONE"); // OFF_PG 는 결제 상태에 반영하지 않는다
            },
            item -> assertExternalRefund(item, 500, 500));

        // 외부 환불로 바뀐 거절(2)은 환불 실패 필터에서 빠지고, 거절 행이 남은 결제(4)는 두 필터에 모두 나온다
        var refundFailed = useCase.search(command(PaymentAdminStatusFilter.REFUND_FAILED, null, null, 0, 20));
        assertThat(refundFailed.contents()).extracting(item -> item.paymentId()).containsExactly("" + (ID + 5), "" + (ID + 4));
        assertThat(refundFailed.contents().getFirst().externalRefund()).isNull();
        assertExternalRefund(refundFailed.contents().get(1), 0, 50);

        // 다른 조건과 AND · 같은 조건의 count · 빈 후속 페이지
        assertThat(useCase.search(command(PaymentAdminStatusFilter.EXTERNAL_REFUNDED, 102L, null, 0, 20)).contents()).singleElement()
            .satisfies(item -> assertExternalRefund(item, 0, 700));
        assertThat(useCase.search(command(PaymentAdminStatusFilter.EXTERNAL_REFUNDED, 105L, null, 0, 20)).contents()).isEmpty();
        assertThat(useCase.search(command(PaymentAdminStatusFilter.EXTERNAL_REFUNDED, null, true, 0, 20)).totalElements()).isZero();
        var later = useCase.search(command(PaymentAdminStatusFilter.EXTERNAL_REFUNDED, null, false, 9, 1));
        assertThat(later.contents()).isEmpty();
        assertThat(later.totalElements()).isEqualTo(4);

        // 필터 없이도 페이지의 외부 환불 합계를 싣는다. 원장이 없거나 외부 환불이 아닌 원장만 있으면 null
        var all = useCase.search(command(null, null, null, 0, 2));
        assertThat(all.contents()).extracting(item -> item.paymentId()).containsExactly("" + (ID + 6), "" + (ID + 5));
        assertThat(all.contents()).allSatisfy(item -> assertThat(item.externalRefund()).isNull());
    }

    private static void assertExternalRefund(PaymentAdminItem item, long pg, long offPg) {
        assertThat(item.externalRefund()).isNotNull();
        assertThat(item.externalRefund().pgAmount()).isEqualTo(MoneyItem.of(pg, "KRW"));
        assertThat(item.externalRefund().offPgAmount()).isEqualTo(MoneyItem.of(offPg, "KRW"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void facadeOpensReadOnlyRepeatableReadWithoutCallerTransaction() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> seed());
        try {
            doAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                return call.callRealMethod();
            }).when(queryAdapter).search(any());
            assertThat(useCase.search(command(null, null, null, 0, 20)).contents()).hasSize(6);
        } finally {
            reset(queryAdapter);
            tx.executeWithoutResult(status -> em.createQuery("delete from PaymentEntity").executeUpdate());
        }
    }

    private void seed() {
        em.persist(entity(1, PaymentStatus.UNKNOWN, false));
        em.persist(entity(2, PaymentStatus.UNKNOWN, false));
        em.persist(entity(3, PaymentStatus.DONE, true));
        em.persist(entity(4, PaymentStatus.DONE, false));
        em.persist(entity(5, PaymentStatus.READY, false));
        em.persist(entity(6, PaymentStatus.FAILED, false));
        em.flush();
        em.clear();
    }

    private static PaymentAdminSearchCommand command(PaymentAdminStatusFilter status, Long orderId, Boolean synced, int page, int size) {
        return new PaymentAdminSearchCommand(status, orderId, synced, page, size);
    }
}
