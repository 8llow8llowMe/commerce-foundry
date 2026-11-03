package com.commercefoundry.domainlayer.notification.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.domain.model.AnomalySource;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.global.config.AnomalyNotificationPushConfig;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AnomalyNotificationPushProcessorTest {
    @Test
    void queueSaturationAndBrokenStreamAreBestEffortAndNeverBlockKafkaCaller(CapturedOutput output) throws Exception {
        var executor = new AnomalyNotificationPushConfig().anomalyNotificationPushExecutor();
        executor.initialize();
        NotificationPushPort port = mock(NotificationPushPort.class);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> thread = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            thread.set(Thread.currentThread().getName());
            calls.incrementAndGet();
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            throw new IllegalStateException("private-stream-error-sentinel");
        }).when(port).push(any());
        var processor = new AnomalyNotificationPushProcessor(port, executor);
        List<Notification> snapshot = List.of(Notification.create(1, AnomalySource.PAYMENT_APPROVED.notification(7, 2)));
        try {
            processor.enqueue(snapshot);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 17; i++) {
                processor.enqueue(snapshot);
            }
            assertThat(calls.get()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(16);
            assertThat(output.getAll()).contains("anomaly notification push queue unavailable");
            release.countDown();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(calls.get()).isEqualTo(17));
            assertThat(thread.get()).startsWith("anomaly-notification-push-");
            assertThat(output.getAll()).doesNotContain("private-stream-error-sentinel");
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
