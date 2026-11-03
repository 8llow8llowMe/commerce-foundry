package com.commercefoundry.domainlayer.notification.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.DltAlertStateRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.DltPartitionCheckpointRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.application.command.DltAlertCommand;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRecipientPort;
import com.commercefoundry.domainlayer.notification.application.service.processor.DltAlertProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:dlt-flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "operations.notifications.dlt-alert.enabled=true", "commerce.messaging.enabled=true", "commerce.messaging.namespace=dlt.test",
    "operations.jobs.worker.enabled=false", "commerce.messaging.outbox.relay.enabled=false", "operations.jobs.planner.enabled=false"
})
@ActiveProfiles("test")
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@EmbeddedKafka(partitions = 1, topics = {"dlt.test.operations.job-run", "dlt.test.operations.job-run.dlt", "dlt.test.order.order-expired.dlt",
    "dlt.test.order.order-canceled.dlt", "dlt.test.payment.payment-approved.dlt"}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class DltAlertFlowTest {
    @MockitoBean
    NotificationRecipientPort recipients;
    @Autowired
    NotificationPushPort push;
    private final java.util.concurrent.BlockingQueue<com.commercefoundry.domainlayer.notification.application.model.NotificationPush> received
        = new java.util.concurrent.LinkedBlockingQueue<>();
    private final com.commercefoundry.domainlayer.notification.application.model.NotificationStream stream = received::add;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    DltAlertProcessor processor;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    DltAlertStateRepository states;
    @Autowired
    DltPartitionCheckpointRepository checkpoints;
    @Autowired
    PlatformTransactionManager transactions;

    @BeforeEach
    void resetData() {
        checkpoints.deleteAll();
        states.deleteAll();
        notifications.deleteAll();
        received.clear();
        push.unregister(7, stream);
        push.unregister(8, stream);
        push.register(7, stream);
        push.register(8, stream);
        when(recipients.findAllActiveAdminIds()).thenReturn(List.of(7L, 8L));
    }

    @Test
    void kafkaToDatabaseAndPushThenSuppressesLaterRecord() throws Exception {
        kafka.send("dlt.test.payment.payment-approved.dlt", "private-key", "private-body").get(10, TimeUnit.SECONDS);
        assertThat(received.poll(15, TimeUnit.SECONDS)).isNotNull();
        assertThat(received.poll(15, TimeUnit.SECONDS)).isNotNull();
        assertThat(notifications.findAll()).hasSize(2).allSatisfy(n -> {
            assertThat(n.getSeverity()).isEqualTo(NotificationSeverity.CRITICAL);
            assertThat(n.getSourceType()).isEqualTo("DLT_ALERT");
            assertThat(n.getLinkPath()).isEqualTo("/admin/payments");
            assertThat(n.getTitle()).doesNotContain("private", "payment", "dlt");
        });
        kafka.send("dlt.test.payment.payment-approved.dlt", "private-key", "private-body").get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && checkpoints.findAll().stream().noneMatch(c -> c.getWatermark() >= 1)) {
            Thread.sleep(25);
        }
        assertThat(checkpoints.findAll()).singleElement().satisfies(c -> assertThat(c.getWatermark()).isGreaterThanOrEqualTo(1));
        assertThat(notifications.count()).isEqualTo(2);
    }

    @Test
    void failedRecipientFetchRedeliversWithoutPayloadLogs(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        when(recipients.findAllActiveAdminIds()).thenThrow(new IllegalStateException("SENTINEL_SECRET_EXCEPTION"))
            .thenReturn(List.of(7L));
        kafka.send("dlt.test.operations.job-run.dlt", "SENTINEL_SECRET_KEY", "SENTINEL_SECRET_BODY").get(10, TimeUnit.SECONDS);
        assertThat(received.poll(20, TimeUnit.SECONDS)).isNotNull();
        verify(recipients, atLeast(2)).findAllActiveAdminIds();
        assertThat(output.getAll()).doesNotContain("SENTINEL_SECRET_EXCEPTION", "SENTINEL_SECRET_KEY", "SENTINEL_SECRET_BODY", ".dlt.dlt");
    }

    @Test
    void rollbackRemovesEveryRecipientStateAndWatermarkAndNeverPushes() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            processor.record(new DltAlertCommand("dlt.test.order.order-expired.dlt", 0, 0), List.of(7L, 8L), NotificationSeverity.WARNING);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(notifications.count()).isZero();
        assertThat(states.count()).isZero();
        assertThat(checkpoints.count()).isZero();
        assertThat(received).isEmpty();
    }
}
