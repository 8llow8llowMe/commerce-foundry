package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static com.commercefoundry.domainlayer.payment.support.PaymentAdminQueryFixtures.entity;
import static com.commercefoundry.domainlayer.payment.support.PaymentAdminQueryFixtures.ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentAdminQueryFixtures.refundHistoryPayments;
import static com.commercefoundry.domainlayer.payment.support.PaymentAdminQueryFixtures.refundHistoryCancellations;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom.PaymentAdminCustomRepositoryImpl;
import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminSearchCriteria;
import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminStatusFilter;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentAdminQueryPort;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.junit.jupiter.api.Test;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=payment",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "spring.jpa.properties.hibernate.generate_statistics=true"})
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, PaymentAdminCustomRepositoryImpl.class, PaymentAdminQueryAdapter.class})
class PaymentAdminQueryAdapterTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");
    @Autowired private TestEntityManager em;
    @Autowired private PaymentAdminQueryPort port;

    @Test
    void postgresFiltersSortingAndCountUseSamePredicates() {
        em.persist(entity(1, PaymentStatus.UNKNOWN, false));
        em.persist(entity(2, PaymentStatus.UNKNOWN, false));
        em.persist(entity(3, PaymentStatus.DONE, true));
        em.flush();
        em.clear();
        var page = port.search(new PaymentAdminSearchCriteria(PaymentAdminStatusFilter.UNKNOWN, null, false, 0, 1));
        assertThat(page.getContent()).singleElement().satisfies(row -> assertThat(row.orderId()).isEqualTo(102L));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(port.search(new PaymentAdminSearchCriteria(PaymentAdminStatusFilter.UNKNOWN, null, false, 9, 1)).getTotalElements()).isEqualTo(2);
        assertThat(port.search(new PaymentAdminSearchCriteria(PaymentAdminStatusFilter.UNKNOWN, 103L, false, 0, 20)).getContent()).isEmpty();
    }

    @Test
    void postgresExistsCorrelatesRejectedHistoryWithoutDuplicatesOrCurrentStatusReplacement() {
        refundHistoryPayments().forEach(em::persist);
        refundHistoryCancellations().forEach(em::persist);
        em.flush();
        em.clear();
        var stats = em.getEntityManager().getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        var history = port.search(new PaymentAdminSearchCriteria(PaymentAdminStatusFilter.REFUND_FAILED, null, false, 0, 20));
        assertThat(history.getContent()).extracting(row -> row.paymentId()).containsExactly(ID + 2, ID + 1);
        assertThat(history.getContent().getFirst().status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(history.getTotalElements()).isEqualTo(2);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(2);
        assertThat(stats.getEntityUpdateCount()).isZero();
        assertThat(stats.getEntityInsertCount()).isZero();
        assertThat(stats.getEntityDeleteCount()).isZero();
        var one = port.search(new PaymentAdminSearchCriteria(PaymentAdminStatusFilter.REFUND_FAILED, 101L, false, 0, 20));
        assertThat(one.getContent()).singleElement().satisfies(row -> assertThat(row.status()).isEqualTo(PaymentStatus.DONE));
        assertThat(one.getTotalElements()).isEqualTo(1);
        assertThat(port.search(new PaymentAdminSearchCriteria(PaymentAdminStatusFilter.REFUND_FAILED, null, null, 0, 20)).getTotalElements()).isEqualTo(3);
        assertThat(port.search(new PaymentAdminSearchCriteria(PaymentAdminStatusFilter.REFUND_FAILED, null, true, 0, 20)).getContent()).singleElement();
        assertThat(port.search(new PaymentAdminSearchCriteria(PaymentAdminStatusFilter.REFUND_FAILED, 103L, false, 0, 20)).getContent()).isEmpty();
        var first = port.search(new PaymentAdminSearchCriteria(PaymentAdminStatusFilter.REFUND_FAILED, null, false, 0, 1));
        assertThat(first.getContent()).singleElement().satisfies(row -> assertThat(row.paymentId()).isEqualTo(ID + 2));
        var later = port.search(new PaymentAdminSearchCriteria(PaymentAdminStatusFilter.REFUND_FAILED, null, false, 4, 1));
        assertThat(later.getContent()).isEmpty();
        assertThat(later.getTotalElements()).isEqualTo(2);
        assertThat(port.search(new PaymentAdminSearchCriteria(PaymentAdminStatusFilter.CANCELED, null, false, 0, 20)).getContent()).singleElement();
        assertThat(port.search(new PaymentAdminSearchCriteria(PaymentAdminStatusFilter.FAILED, null, false, 0, 20)).getContent()).singleElement()
            .satisfies(row -> assertThat(row.paymentId()).isEqualTo(ID + 5));
    }
}
