package com.commercefoundry.domainlayer.payment;

import com.commercefoundry.messaging.support.MessagingKafkaContainers;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=payment"})
class OrderPaymentReplyKafkaPostgresTest extends OrderPaymentReplyKafkaContract {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");
    @Container static final KafkaContainer KAFKA = MessagingKafkaContainers.kafka();
    @Override protected String bootstrapServers() { return KAFKA.getBootstrapServers(); }
}
