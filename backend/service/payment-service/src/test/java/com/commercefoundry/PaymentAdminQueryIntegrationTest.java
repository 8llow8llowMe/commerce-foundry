package com.commercefoundry;

import static com.commercefoundry.domainlayer.payment.support.PaymentAdminQueryFixtures.ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentAdminQueryFixtures.entity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

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
    void readsExactlyTwoStatementsWithoutChangingVersions() {
        seed();
        Long version = em.find(PaymentEntity.class, ID + 1).getVersion();
        em.clear();
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        assertThat(useCase.search(command(null, null, null, 0, 20)).contents()).hasSize(6);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(2);
        em.flush();
        em.clear();
        assertThat(em.find(PaymentEntity.class, ID + 1).getVersion()).isEqualTo(version);
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
