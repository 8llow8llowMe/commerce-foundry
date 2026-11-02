package com.commercefoundry.domainlayer.order.adapter.in.event;

import com.commercefoundry.domainlayer.order.support.PaymentApprovalKafkaContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

class PaymentApprovalEmbeddedKafkaTest extends PaymentApprovalKafkaContract {
    static final EmbeddedKafkaBroker BROKER = new EmbeddedKafkaKraftBroker(1, 1)
        .brokerProperties(java.util.Map.of("offsets.topic.num.partitions", "1", "offsets.topic.replication.factor", "1",
            "group.initial.rebalance.delay.ms", "0"));
    @BeforeAll static void start() { BROKER.afterPropertiesSet(); }
    @AfterAll static void stop() { BROKER.destroy(); }
    @Override protected String bootstrapServers() { return BROKER.getBrokersAsString(); }
}
