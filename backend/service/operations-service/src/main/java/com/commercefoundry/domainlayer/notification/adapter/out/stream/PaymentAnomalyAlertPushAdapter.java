package com.commercefoundry.domainlayer.notification.adapter.out.stream;

import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertPushPort;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;

/** 실행기와 종료 책임을 단독 소유한다. 생성만으로 worker 스레드를 시작하지 않는다. */
@Slf4j
@Component
public class PaymentAnomalyAlertPushAdapter implements PaymentAnomalyAlertPushPort, ApplicationContextAware, ApplicationListener<ContextClosedEvent>, Ordered {
    private final NotificationPushPort push;
    private ApplicationContext context;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(16), task -> {
            var thread = new Thread(task, "payment-anomaly-push");
            thread.setDaemon(false);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    public PaymentAnomalyAlertPushAdapter(NotificationPushPort push) {
        this.push = push;
    }

    @Override
    public void setApplicationContext(ApplicationContext context) { this.context = context; }

    @Override
    public void enqueue(List<Notification> notifications) {
        if (notifications == null || notifications.isEmpty() || notifications.size() > 5000) {
            throw new IllegalArgumentException("Invalid anomaly push batch");
        }
        var batch = List.copyOf(notifications);
        if (closed.get()) {
            log.warn("payment_anomaly_push_rejected reason=closed");
            return;
        }
        try {
            executor.execute(() -> deliver(batch));
        } catch (RejectedExecutionException ignored) {
            log.warn("payment_anomaly_push_rejected reason={}", closed.get() ? "closed" : "capacity");
        }
    }

    private void deliver(List<Notification> batch) {
        for (var notification : batch) {
            // 검사 직후 종료와 send가 경합할 수 있다. 이미 진행 중인 전송의 강제 종료는 보장하지 않는다.
            if (closed.get() || Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                push.push(notification);
            } catch (RuntimeException ignored) {
                log.warn("payment_anomaly_push_failed reason=delivery");
            }
        }
    }

    private final AtomicBoolean closeStarted = new AtomicBoolean();

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (event.getApplicationContext() != context) { return; }
        // lifecycle stop로 수집기를 기다리기 전에 차단·대기 폐기·interrupt 요청을 한다. 여기서는 기다리지 않는다.
        stopAccepting();
    }

    private void stopAccepting() {
        if (closed.compareAndSet(false, true)) { executor.shutdownNow(); }
    }

    @PreDestroy
    public void close() {
        if (!closeStarted.compareAndSet(false, true)) { return; }
        stopAccepting();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("payment_anomaly_push_shutdown reason=timeout");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            log.warn("payment_anomaly_push_shutdown reason=interrupted");
        }
    }
}
