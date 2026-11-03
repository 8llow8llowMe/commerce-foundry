package com.commercefoundry.domainlayer.notification.adapter.out.stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.domain.model.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@ExtendWith(OutputCaptureExtension.class)
class PaymentOutboxAlertPushAdapterTest {
    @Test
    void boundedQueueDropsExcessWithoutBlockingAndDrainsAfterRelease(CapturedOutput output) throws Exception {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(16); executor.initialize();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delivered = new CountDownLatch(17);
        var push = mock(NotificationPushPort.class);
        doAnswer(i -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Push barrier timeout");
            delivered.countDown(); return null;
        }).when(push).push(any());
        var adapter = new PaymentOutboxAlertPushAdapter(push, executor);
        var batch = List.of(Notification.create(1, new NotificationRequest(NotificationKind.ANOMALY, NotificationSeverity.INFO,
            "Recovered", null, 7, "PAYMENT_OUTBOX_ALERT", 1)));
        try {
            adapter.enqueue(batch);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 16; i++) adapter.enqueue(batch);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> adapter.enqueue(batch));
            assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(16);
            assertThat(output.getAll()).contains("push queue unavailable");
            release.countDown();
            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
            verify(push, times(17)).push(any());
        } finally { release.countDown(); executor.shutdown(); }
    }
}
