package com.commercefoundry.domainlayer.order.adapter.in.event;

import com.commercefoundry.domainlayer.order.support.PaymentApprovalKafkaContract;
import com.commercefoundry.messaging.support.MessagingKafkaContainers;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@EnabledWithDockerOrOnCi
class PaymentApprovalKafkaContainerTest extends PaymentApprovalKafkaContract {
    @Container static final KafkaContainer KAFKA = MessagingKafkaContainers.kafka();
    @Override protected String bootstrapServers() { return KAFKA.getBootstrapServers(); }
}
