package com.commercefoundry.messaging.cleanup;

import com.commercefoundry.messaging.consume.ProcessedEventStore;
import com.commercefoundry.messaging.outbox.OutboxStore;
import com.commercefoundry.messaging.properties.MessagingProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

/**
 * 메시징 기록 정리 (#378) — 보존 기간이 지난 발행 완료 outbox 행과 처리 기록(inbox)을 지운다. 단일 실행 락 아래에서 돈다.
 *
 * <p>릴레이와 따로 둔다 — 받기만 하는 서비스(outbox 없음, 예 product-service)도 처리 기록이 지워져야 한다. 서비스가 둔 저장소만 정리한다.
 * 락 이름은 릴레이에 있던 때의 {@value #CLEANUP_LOCK} 그대로다 — 롤링 배포 중 구 · 신 인스턴스가 같은 락을 쓴다.
 */
@Slf4j
public class MessagingCleanup {

    static final String CLEANUP_LOCK = "commerce-messaging-outbox-cleanup";
    /** 정리 한 번에 도는 최대 배치 수 — 밀린 정리가 메시징 백그라운드 스레드를 오래 잡지 않게 한다(남은 것은 다음 주기). */
    static final int MAX_CLEANUP_BATCHES = 100;

    private final OutboxStore outboxStore;
    private final ProcessedEventStore processedEventStore;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final MessagingProperties properties;
    private final Clock clock;

    /**
     * @param outboxStore 정리할 outbox. 이벤트를 내지 않는 서비스면 null
     * @param processedEventStore 정리할 inbox. 이벤트를 받지 않는 서비스면 null
     * @throws IllegalArgumentException 둘 다 null 이면 — 정리할 것이 없는 정리기는 만들지 않는다
     */
    public MessagingCleanup(
        OutboxStore outboxStore,
        ProcessedEventStore processedEventStore,
        LockingTaskExecutor lockingTaskExecutor,
        MessagingProperties properties,
        Clock clock
    ) {
        if (outboxStore == null && processedEventStore == null) {
            throw new IllegalArgumentException("정리할 저장소(OutboxStore · ProcessedEventStore)가 하나는 있어야 합니다.");
        }
        this.outboxStore = outboxStore;
        this.processedEventStore = processedEventStore;
        this.lockingTaskExecutor = lockingTaskExecutor;
        this.properties = properties;
        this.clock = clock;
    }

    /** 한 주기 — 락을 쥔 인스턴스만 지운다. 예외를 밖으로 내지 않는다(스케줄 스레드가 멈추지 않게). */
    public void cleanupOnce() {
        try {
            lockingTaskExecutor.executeWithLock((Runnable) () -> {
                Instant now = clock.instant();
                int batchSize = properties.outbox().cleanup().batchSize();
                int deleted = outboxStore == null ? 0
                    : drain(() -> outboxStore.deletePublishedBefore(now.minus(properties.outbox().cleanup().retention()), batchSize), batchSize);
                int processed = processedEventStore == null ? 0
                    : drain(() -> processedEventStore.deleteProcessedBefore(now.minus(properties.inbox().retention()), batchSize), batchSize);
                if (deleted + processed > 0) {
                    log.info("messaging cleanup. outboxDeleted={}, processedEventDeleted={}", deleted, processed);
                }
            }, new LockConfiguration(clock.instant(), CLEANUP_LOCK, properties.outbox().relay().lockAtMostFor(), Duration.ZERO));
        } catch (RuntimeException exception) {
            // DB · 락 오류 상세가 원문으로 남지 않게 타입만
            log.warn("messaging cleanup failed. error={}", exception.getClass().getSimpleName());
        }
    }

    /** 한 번에 {@code batchSize} 씩, 덜 지워지거나 {@link #MAX_CLEANUP_BATCHES} 까지 — 한 문장으로 대량 삭제하지 않는다. */
    private static int drain(IntSupplier deleteBatch, int batchSize) {
        int total = 0;
        int deleted;
        int batches = 0;
        do {
            deleted = deleteBatch.getAsInt();
            total += deleted;
        } while (deleted >= batchSize && ++batches < MAX_CLEANUP_BATCHES);
        return total;
    }
}
