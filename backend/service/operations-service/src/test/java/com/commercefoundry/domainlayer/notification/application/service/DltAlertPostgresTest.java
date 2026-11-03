package com.commercefoundry.domainlayer.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.DltAlertStateRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.DltPartitionCheckpointRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.application.command.DltAlertCommand;
import com.commercefoundry.domainlayer.notification.application.service.processor.DltAlertProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "operations.jobs.worker.enabled=false"})
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
class DltAlertPostgresTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");
    @Autowired DltAlertProcessor processor;
    @Autowired NotificationRepository notifications;
    @Autowired DltAlertStateRepository states;
    @Autowired DltPartitionCheckpointRepository checkpoints;
    @Autowired PlatformTransactionManager transactions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.commercefoundry.domainlayer.notification.application.port.out.NotificationRepositoryPort notificationPort;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort push;

    @BeforeEach
    void reset() {
        org.mockito.Mockito.reset(notificationPort, push);
        checkpoints.deleteAll();
        states.deleteAll();
        notifications.deleteAll();
    }

    @Test
    void concurrentFirstInsertAndPartitionsHaveOneSharedSuppressionWindow() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = java.util.stream.IntStream.range(0, 4).mapToObj(partition -> executor.submit((java.util.concurrent.Callable<Void>) () -> {
                start.await(10, TimeUnit.SECONDS);
                for (int attempt = 0; attempt < 20; attempt++) {
                    try {
                        processor.record(new DltAlertCommand("test.topic.dlt", partition, 0), List.of(7L, 8L), NotificationSeverity.WARNING);
                        return null;
                    } catch (org.springframework.dao.DataAccessException retry) {
                        Thread.sleep(25);
                        // A lost first-insert/NOWAIT race is retried with a fresh proxy transaction.
                    }
                }
                throw new AssertionError("Retries exhausted");
            })).toList();
            start.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(states.count()).isEqualTo(1);
        assertThat(checkpoints.count()).isEqualTo(4);
        assertThat(notifications.count()).isEqualTo(2);
    }

    @Test
    void rollbackAllRecipientsAndWatermarkTogether() {
        var inserts = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (inserts.incrementAndGet() == 2) {
                throw new IllegalStateException("Second recipient insert failed");
            }
            return invocation.callRealMethod();
        }).when(notificationPort).insert(org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> processor.record(new DltAlertCommand("test.topic.dlt", 0, 0), List.of(7L, 8L), NotificationSeverity.WARNING))
            .isInstanceOf(IllegalStateException.class);
        assertThat(states.count()).isZero();
        assertThat(checkpoints.count()).isZero();
        assertThat(notifications.count()).isZero();
        org.mockito.Mockito.verify(push, org.mockito.Mockito.never()).push(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.reset(notificationPort);
        processor.record(new DltAlertCommand("test.topic.dlt", 0, 0), List.of(7L, 8L), NotificationSeverity.WARNING);
        assertThat(notifications.count()).isEqualTo(2);
        assertThat(checkpoints.count()).isEqualTo(1);
        org.mockito.Mockito.verify(push, org.mockito.Mockito.times(2)).push(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void actualPessimisticQueryUsesNowaitOnLockedTopic() throws Exception {
        processor.record(new DltAlertCommand("test.topic.dlt", 0, 0), List.of(7L), NotificationSeverity.WARNING);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                states.findLocked("test.topic.dlt").orElseThrow();
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                    () -> assertThatThrownBy(() -> processor.record(
                        new DltAlertCommand("test.topic.dlt", 0, 1), List.of(7L), NotificationSeverity.WARNING))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class));
                assertThat(checkpoints.findAll()).singleElement().satisfies(c -> assertThat(c.getWatermark()).isZero());
                assertThat(notifications.count()).isEqualTo(1);
            } finally {
                release.countDown();
            }
            holder.get(15, TimeUnit.SECONDS);
        }
    }
}
