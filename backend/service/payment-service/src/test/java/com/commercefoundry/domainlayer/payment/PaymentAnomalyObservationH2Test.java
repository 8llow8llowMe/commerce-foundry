package com.commercefoundry.domainlayer.payment;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.PaymentAnomalyObservationAdapter;
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
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, PaymentAnomalyObservationAdapter.class})
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:payment-anomaly-observation-query;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS payment",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.jpa.properties.hibernate.default_schema=payment",
    "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true"
})
class PaymentAnomalyObservationH2Test extends PaymentAnomalyObservationQueryContract {
}
