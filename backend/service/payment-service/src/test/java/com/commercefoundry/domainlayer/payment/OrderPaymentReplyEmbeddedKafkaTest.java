package com.commercefoundry.domainlayer.payment;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:reply-kafka;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS payment")
class OrderPaymentReplyEmbeddedKafkaTest extends OrderPaymentReplyKafkaContract {
    static final EmbeddedKafkaBroker BROKER = new EmbeddedKafkaKraftBroker(1, 1)
        .brokerProperties(Map.of("offsets.topic.num.partitions", "1", "offsets.topic.replication.factor", "1", "group.initial.rebalance.delay.ms", "0"));
    @BeforeAll static void startBroker() { BROKER.afterPropertiesSet(); }
    @AfterAll static void stopBroker() { BROKER.destroy(); }
    @Override protected String bootstrapServers() { return BROKER.getBrokersAsString(); }
}
