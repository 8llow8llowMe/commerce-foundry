package com.commercefoundry.domainlayer.notification.adapter.out.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertPushPort;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyAlertPushProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentAnomalyAlertPushAdapterTest {
    private static final AtomicLong IDS = new AtomicLong(1);

    @Test
    void validatesWholeBatchAndProcessorPassesImmutableSnapshot() {
        var port = mock(PaymentAnomalyAlertPushPort.class);
        var processor = new PaymentAnomalyAlertPushProcessor(port);
        var adapter = new PaymentAnomalyAlertPushAdapter(mock(NotificationPushPort.class));
        try {
            var nullItem = new ArrayList<Notification>();
            nullItem.add(null);
            for (var input : List.of(List.<Notification>of(), batch(5001), nullItem)) {
                assertThatThrownBy(() -> processor.enqueue(input)).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> adapter.enqueue(input)).isInstanceOf(RuntimeException.class);
            }
            assertThatThrownBy(() -> processor.enqueue(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> adapter.enqueue(null)).isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(port);
            doAnswer(call -> {
                List<Notification> captured = call.getArgument(0);
                assertThatThrownBy(captured::clear).isInstanceOf(UnsupportedOperationException.class);
                return null;
            }).when(port).enqueue(any());
            processor.enqueue(new ArrayList<>(batch(1)));
            verify(port).enqueue(any());
            assertThat(executor(adapter).getPoolSize()).isZero();
        } finally {
            adapter.close();
        }
    }

    @Test
    void distinctMaximumBatchesOccupyOneWorkerAndSixteenSlotsThenDrain() throws Exception {
        var push = new NotificationPushAdapter();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var count = new AtomicInteger();
        var delivered = new HashSet<String>();
        push.register(1, message -> {
            if (count.getAndIncrement() == 0) {
                entered.countDown();
                await(release);
            }
            assertThat(delivered.add(message.notificationId())).isTrue();
        });
        var adapter = new PaymentAnomalyAlertPushAdapter(push);
        var logger = (Logger) LoggerFactory.getLogger(PaymentAnomalyAlertPushAdapter.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            adapter.enqueue(batch(5000));
            await(entered);
            for (int i = 0; i < 16; i++) {
                var mutable = new ArrayList<>(batch(5000));
                adapter.enqueue(mutable);
                mutable.clear();
            }
            var executor = executor(adapter);
            assertThat(executor.getActiveCount()).isEqualTo(1);
            assertThat(executor.getQueue()).hasSize(16);
            assertThat(executor.getTaskCount()).isEqualTo(17);
            var rejected = batch(5000);
            adapter.enqueue(rejected);
            assertThat(logs.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("payment_anomaly_push_rejected reason=capacity");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(executor.getTaskCount()).isEqualTo(17);
            assertThat(executor.getQueue()).hasSize(16);
            release.countDown();
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(executor.getCompletedTaskCount()).isEqualTo(17);
                assertThat(executor.getActiveCount()).isZero();
                assertThat(executor.getQueue()).isEmpty();
            });
            assertThat(count).hasValue(85_000);
            assertThat(delivered).hasSize(85_000);
            assertThat(delivered).doesNotContain(Long.toString(rejected.getFirst().id()));
            adapter.enqueue(batch(1));
            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(count).hasValue(85_001));
        } finally {
            release.countDown();
            adapter.close();
            assertThat(executor(adapter).awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void actualStreamIoAndRuntimeFailuresAreIsolatedAndRemainingStreamsContinue() throws Exception {
        var push = new NotificationPushAdapter();
        var ioCalls = new AtomicInteger();
        var runtimeCalls = new AtomicInteger();
        var received = new AtomicInteger();
        push.register(1, message -> { ioCalls.incrementAndGet(); throw new IOException("synthetic sentinel"); });
        push.register(1, message -> { runtimeCalls.incrementAndGet(); throw new IllegalStateException("synthetic sentinel"); });
        push.register(1, message -> received.incrementAndGet());
        var adapter = new PaymentAnomalyAlertPushAdapter(push);
        try {
            adapter.enqueue(batch(2));
            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).hasValue(2));
            assertThat(ioCalls).hasValue(1);
            assertThat(runtimeCalls).hasValue(1);
        } finally {
            adapter.close();
            assertThat(executor(adapter).awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void individualPortRuntimeFailureDoesNotAbortRemainingNotifications() throws Exception {
        var push = mock(NotificationPushPort.class);
        var received = new AtomicInteger();
        doAnswer(call -> {
            if (received.getAndIncrement() == 0) { throw new IllegalStateException("synthetic sentinel"); }
            return null;
        }).when(push).push(any());
        var adapter = new PaymentAnomalyAlertPushAdapter(push);
        try {
            adapter.enqueue(batch(2));
            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).hasValue(2));
        } finally {
            adapter.close();
            assertThat(executor(adapter).awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void contextCloseInterruptsCooperativeStreamDiscardsQueueAndRejectsNewBatches() throws Exception {
        var push = new NotificationPushAdapter();
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        push.register(1, message -> {
            calls.incrementAndGet();
            entered.countDown();
            try { release.await(); } catch (InterruptedException error) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        var context = context(push);
        var adapter = context.getBean(PaymentAnomalyAlertPushAdapter.class);
        try {
            assertThat(executor(adapter).getPoolSize()).isZero();
            adapter.enqueue(batch(2));
            await(entered);
            adapter.enqueue(batch(5000));
            context.close();
            await(interrupted);
            assertThat(executor(adapter).isTerminated()).isTrue();
            assertThat(executor(adapter).getQueue()).isEmpty();
            adapter.enqueue(batch(1));
            adapter.close();
            assertThat(calls).hasValue(1);
            assertThat(executor(adapter).getLargestPoolSize()).isEqualTo(1);
        } finally {
            release.countDown();
            context.close();
            assertThat(executor(adapter).awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void interruptIgnoringStreamOutlivesCloseWaitAndIsAlwaysReleasedByTest() throws Exception {
        var push = new NotificationPushAdapter();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        push.register(1, message -> {
            worker.set(Thread.currentThread());
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { /* 비협조 전송 모사 */ }
            }
        });
        var adapter = new PaymentAnomalyAlertPushAdapter(push);
        try {
            var submitter = new Thread(() -> adapter.enqueue(batch(2)));
            submitter.setDaemon(true);
            submitter.start();
            submitter.join(10_000);
            assertThat(submitter.isAlive()).isFalse();
            await(entered);
            assertThat(worker.get().isDaemon()).isFalse();
            adapter.close();
            assertThat(executor(adapter).isShutdown()).isTrue();
            assertThat(executor(adapter).isTerminated()).isFalse();
            adapter.enqueue(batch(1));
            adapter.close();
        } finally {
            release.countDown();
            adapter.close();
            assertThat(executor(adapter).awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            worker.get().join(10_000);
            assertThat(worker.get().isAlive()).isFalse();
        }
    }

    @Test
    void closeBetweenLoopCheckAndSendAllowsOneRacingSendButDiscardsTheRest() throws Exception {
        var delegate = new NotificationPushAdapter();
        var sends = new AtomicInteger();
        delegate.register(1, message -> sends.incrementAndGet());
        var checked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var calls = new AtomicInteger();
        var wrapper = mock(NotificationPushPort.class);
        doAnswer(call -> {
            worker.set(Thread.currentThread());
            calls.incrementAndGet();
            checked.countDown();
            // Adapter의 closed 검사를 통과한 뒤 실제 send 직전의 비협조 구간을 고정한다.
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { /* gate 유지 */ }
            }
            delegate.push(call.getArgument(0));
            return null;
        }).when(wrapper).push(any());
        var adapter = new PaymentAnomalyAlertPushAdapter(wrapper);
        var closer = Executors.newSingleThreadExecutor();
        try {
            adapter.enqueue(batch(2));
            await(checked);
            adapter.enqueue(batch(1));
            assertThat(executor(adapter).getQueue()).hasSize(1);
            var close = closer.submit(adapter::close);
            close.get(10, TimeUnit.SECONDS);
            assertThat(executor(adapter).isShutdown()).isTrue();
            assertThat(executor(adapter).isTerminated()).isFalse();
            assertThat(executor(adapter).getQueue()).isEmpty();
            assertThat(sends).hasValue(0);
            release.countDown();
            assertThat(executor(adapter).awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(sends).hasValue(1);
            assertThat(calls).hasValue(1);
            assertThat(executor(adapter).getCompletedTaskCount()).isEqualTo(1);
        } finally {
            release.countDown();
            adapter.close();
            closer.shutdownNow();
            assertThat(closer.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(executor(adapter).awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            if (worker.get() != null) {
                worker.get().join(10_000);
                assertThat(worker.get().isAlive()).isFalse();
            }
        }
    }

    @Test
    void closeRestoresCallerInterruptAndEnqueueCloseRaceDoesNotLeakRejection() throws Exception {
        var push = new NotificationPushAdapter();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        push.register(1, message -> { entered.countDown(); await(release); });
        var adapter = new PaymentAnomalyAlertPushAdapter(push);
        var callers = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            adapter.enqueue(batch(2));
            await(entered);
            var enqueue = callers.submit(() -> {
                await(start);
                for (int i = 0; i < 32; i++) { adapter.enqueue(batch(1)); }
            });
            var close = callers.submit(() -> {
                await(start);
                Thread.currentThread().interrupt();
                adapter.close();
                boolean restored = Thread.interrupted();
                assertThat(restored).isTrue();
            });
            start.countDown();
            enqueue.get(10, TimeUnit.SECONDS);
            close.get(10, TimeUnit.SECONDS);
            assertThat(executor(adapter).getQueue()).isEmpty();
            assertThat(executor(adapter).getLargestPoolSize()).isEqualTo(1);
        } finally {
            start.countDown();
            release.countDown();
            adapter.close();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(executor(adapter).awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void unconnectedSpringContextStartsNoWorkerAndClosesWithoutWork() {
        try (var context = context(new NotificationPushAdapter())) {
            var adapter = context.getBean(PaymentAnomalyAlertPushAdapter.class);
            assertThat(executor(adapter).getPoolSize()).isZero();
            assertThat(executor(adapter).getTaskCount()).isZero();
            assertThat(executor(adapter).getQueue()).isEmpty();
        }
    }

    private static AnnotationConfigApplicationContext context(NotificationPushPort push) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(NotificationPushPort.class, () -> push);
        context.register(PaymentAnomalyAlertPushAdapter.class, PaymentAnomalyAlertPushProcessor.class);
        context.refresh();
        return context;
    }

    private static ThreadPoolExecutor executor(PaymentAnomalyAlertPushAdapter adapter) {
        return (ThreadPoolExecutor) ReflectionTestUtils.getField(adapter, "executor");
    }

    private static List<Notification> batch(int size) {
        return IntStream.range(0, size).mapToObj(i -> {
            long id = IDS.getAndIncrement();
            return new Notification(id, 1, NotificationKind.ANOMALY, NotificationSeverity.WARNING, "결제 후보 확인", "/admin/payments",
                "PAYMENT_ANOMALY_ALERT", id, null, null, null);
        }).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) { throw new AssertionError("synthetic latch watchdog"); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
