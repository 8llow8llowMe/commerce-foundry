package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentRepository;
import com.commercefoundry.domainlayer.payment.application.mapper.PaymentMapperImpl;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
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
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=payment",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, PaymentCancelPersistenceAdapter.class, PaymentMapperImpl.class})
class PaymentCancelPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private PaymentMapperImpl mapper;

    @Autowired
    private PaymentCancelPersistenceAdapter adapter;

    @Test
    void locksPaymentAndPersistsClaimOnceAcrossStatusUpdate() {
        var payment = PaymentFixtures.done();
        payments.saveAndFlush(mapper.toEntity(payment));

        PaymentCancel pending = new PaymentCancel(42, payment.id(), PaymentFixtures.krw(10_000), PaymentCancel.Status.PENDING);
        adapter.save(pending);
        adapter.save(adapter.findByClaimId(42).orElseThrow().succeeded());

        assertThat(adapter.lockPayment(payment.id())).isPresent();
        assertThat(adapter.findByPaymentId(payment.id())).singleElement()
            .satisfies(cancel -> {
                assertThat(cancel.status()).isEqualTo(PaymentCancel.Status.SUCCEEDED);
                assertThat(cancel.createdAt()).isNotNull();
            });
    }
}
