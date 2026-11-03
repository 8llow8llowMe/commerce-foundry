package com.commercefoundry.domainlayer.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRecipientPort;
import com.commercefoundry.domainlayer.notification.application.model.NotificationPush;
import com.commercefoundry.messaging.support.MessagingKafkaContainers;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "commerce.messaging.enabled=true", "commerce.messaging.namespace=dlt.test",
    "operations.notifications.dlt-alert.enabled=true", "operations.jobs.worker.enabled=false", "commerce.messaging.outbox.relay.enabled=false"})
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
class DltAlertKafkaPostgresTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");
    @Container
    static final KafkaContainer KAFKA = MessagingKafkaContainers.kafka();

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            var topics = new java.util.ArrayList<>(DltAlertEventFacade.TOPICS);
            topics.add("operations.job-run");
            admin.createTopics(topics.stream().map(t -> new NewTopic("dlt.test." + t, 1, (short) 1)).toList()).all().get(20, TimeUnit.SECONDS);
        }
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @MockitoBean NotificationRecipientPort recipients;
    @Autowired NotificationPushPort push;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired NotificationRepository notifications;

    @Test
    void kafkaCommitsNotificationBeforeDeliveringSseMessage() throws Exception {
        when(recipients.findAllActiveAdminIds()).thenReturn(List.of(7L));
        var received = new LinkedBlockingQueue<NotificationPush>();
        push.register(7, message -> {
            assertThat(notifications.count()).isEqualTo(1);
            received.add(message);
        });
        kafka.send("dlt.test.payment.payment-approved.dlt", "private-key", "private-body").get(10, TimeUnit.SECONDS);
        assertThat(received.poll(20, TimeUnit.SECONDS)).isNotNull();
        assertThat(notifications.findAll()).singleElement().satisfies(n -> assertThat(n.getSourceType()).isEqualTo("DLT_ALERT"));
    }
}
