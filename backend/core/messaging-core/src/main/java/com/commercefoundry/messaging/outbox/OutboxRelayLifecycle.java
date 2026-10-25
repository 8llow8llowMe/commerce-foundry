package com.commercefoundry.messaging.outbox;

import com.commercefoundry.messaging.properties.MessagingProperties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * 릴레이 · 정리 주기를 돌리는 전용 스레드 하나({@code outbox-relay}). {@code TaskScheduler} 빈을 만들지 않는다 —
 * 만들면 Boot 의 기본 {@code taskScheduler} 가 빠진다(#212 에서 확인한 교훈).
 *
 * <p>phase {@code MAX_VALUE - 2} — SmartLifecycle 은 phase 가 높은 것부터 멈추므로 작업 워커(MAX · MAX-1)보다는 늦게,
 * 리스너 컨테이너(MAX-100) · 웹 서버보다는 먼저 멈춘다. 그래서 종료 중인 리스너 · 요청이 커밋한 outbox 행은 이 인스턴스가 보내지 않고
 * 다음 기동(또는 다른 인스턴스)이 보낸다 — 행은 남으니 유실은 없다. 멈출 때는 진행 중인 주기를 발행 대기 시간만큼 기다린 뒤 끊는다.
 */
@Slf4j
public class OutboxRelayLifecycle implements SmartLifecycle {

    public static final int PHASE = Integer.MAX_VALUE - 2;

    private final OutboxRelay relay;
    private final MessagingProperties properties;
    private volatile ScheduledExecutorService executor;

    /** @param relay 돌릴 릴레이. 메시징 · 릴레이가 꺼졌거나 이 서비스가 outbox 를 두지 않았으면 null — 아무것도 돌리지 않는다 */
    public OutboxRelayLifecycle(OutboxRelay relay, MessagingProperties properties) {
        this.relay = relay;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (executor != null || relay == null) {
            return;
        }
        MessagingProperties.Relay relayProperties = properties.outbox().relay();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
        long interval = relayProperties.interval().toMillis();
        executor.scheduleWithFixedDelay(relay::relayOnce, interval, interval, TimeUnit.MILLISECONDS);
        if (properties.outbox().cleanup().enabled()) {
            long cleanup = properties.outbox().cleanup().interval().toMillis();
            executor.scheduleWithFixedDelay(relay::cleanupOnce, cleanup, cleanup, TimeUnit.MILLISECONDS);
        }
        log.info("outbox relay started. interval={}, batchSize={}", relayProperties.interval(), relayProperties.batchSize());
    }

    @Override
    public synchronized void stop() {
        ScheduledExecutorService current = executor;
        if (current == null) {
            return;
        }
        executor = null;
        current.shutdown();
        try {
            if (!current.awaitTermination(properties.outbox().relay().publishTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException exception) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("outbox relay stopped.");
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
