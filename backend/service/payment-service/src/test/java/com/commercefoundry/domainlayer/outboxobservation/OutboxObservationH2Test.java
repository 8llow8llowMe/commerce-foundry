package com.commercefoundry.domainlayer.outboxobservation;

import com.commercefoundry.domainlayer.outboxobservation.adapter.out.persistence.OutboxObservationRepositoryAdapter;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, OutboxObservationRepositoryAdapter.class})
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:outbox-observation-query;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS payment",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.jpa.properties.hibernate.default_schema=payment",
    "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true"
})
class OutboxObservationH2Test extends OutboxObservationQueryContract {
}
