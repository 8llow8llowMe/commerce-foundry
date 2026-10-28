package com.commercefoundry.messaging.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.messaging.consume.ProcessedEventStore;
import com.commercefoundry.messaging.outbox.OutboxRow;
import com.commercefoundry.messaging.outbox.OutboxStore;
import com.commercefoundry.messaging.properties.MessagingProperties;
import com.commercefoundry.messaging.support.InMemoryOutboxStore;
import com.commercefoundry.messaging.support.InMemoryProcessedEventStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 기록 정리 — 서비스가 둔 저장소(outbox · inbox)만, 보존 기간이 지난 것만, 배치 상한까지 지운다. 실패해도 밖으로 던지지 않는다. */
class MessagingCleanupTest {

    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofDays(8));
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final List<LockConfiguration> locks = new ArrayList<>();
    private LockingTaskExecutor lockingTaskExecutor;
    private InMemoryOutboxStore outbox;
    private InMemoryProcessedEventStore processed;

    @BeforeEach
    void setUp() {
        lockingTaskExecutor = new DefaultLockingTaskExecutor(configuration -> {
            locks.add(configuration);
            return Optional.of(() -> { });
        });
        outbox = new InMemoryOutboxStore();
        processed = new InMemoryProcessedEventStore();
    }

    private static MessagingProperties properties(int batchSize) {
        MessagingProperties.Outbox outbox = new MessagingProperties.Outbox(null,
            new MessagingProperties.Cleanup(true, Duration.ofHours(1), Duration.ofDays(7), batchSize));
        return new MessagingProperties(true, "commerce-foundry.test", "messaging-test", List.of(), 65536, outbox, null, null);
    }

    private static OutboxRow row(String aggregateId) {
        return new OutboxRow(null, "sample", aggregateId, "sample-created", 1, "{}", NOW, 0);
    }

    @Test
    @DisplayName("outbox · inbox 둘 다 — 보존 기간(7일)이 지난 발행 완료 행 · 처리 기록만 지운다")
    void cleansBoth() {
        outbox.put(row("old"), OLD);
        outbox.put(row("recent"), NOW.minusSeconds(3600));
        processed.record("group", UUID.randomUUID(), "sample-created", OLD);
        processed.record("group", UUID.randomUUID(), "sample-created", NOW);

        new MessagingCleanup(outbox, processed, lockingTaskExecutor, properties(1000), CLOCK).cleanupOnce();

        assertThat(outbox.rows()).extracting(OutboxRow::aggregateId).containsExactly("recent");
        assertThat(processed.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("inbox 만 — 받기만 하는 서비스(outbox 없음)도 처리 기록이 지워진다")
    void cleansInboxOnly() {
        processed.record("group", UUID.randomUUID(), "sample-created", OLD);
        processed.record("group", UUID.randomUUID(), "sample-created", NOW);

        new MessagingCleanup(null, processed, lockingTaskExecutor, properties(1000), CLOCK).cleanupOnce();

        assertThat(processed.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("outbox 만 — inbox 가 없어도 발행 완료 행이 지워진다")
    void cleansOutboxOnly() {
        outbox.put(row("old"), OLD);
        outbox.put(row("recent"), NOW.minusSeconds(3600));

        new MessagingCleanup(outbox, null, lockingTaskExecutor, properties(1000), CLOCK).cleanupOnce();

        assertThat(outbox.rows()).extracting(OutboxRow::aggregateId).containsExactly("recent");
    }

    @Test
    @DisplayName("배치 반복 — 배치가 가득 차면 다시 지우고, 덜 차면 멈춘다")
    void repeatsWhileBatchFull() {
        for (int i = 0; i < 5; i++) {
            processed.record("group", UUID.randomUUID(), "sample-created", OLD);
        }

        new MessagingCleanup(null, processed, lockingTaskExecutor, properties(2), CLOCK).cleanupOnce();

        assertThat(processed.size()).isZero();
    }

    @Test
    @DisplayName("배치 상한 — 계속 가득 차도 한 주기에 MAX_CLEANUP_BATCHES 번까지만 지운다(남은 것은 다음 주기)")
    void stopsAtMaxBatches() {
        ProcessedEventStore store = mock(ProcessedEventStore.class);
        given(store.deleteProcessedBefore(any(Instant.class), anyInt())).willReturn(2);

        new MessagingCleanup(null, store, lockingTaskExecutor, properties(2), CLOCK).cleanupOnce();

        verify(store, times(MessagingCleanup.MAX_CLEANUP_BATCHES)).deleteProcessedBefore(NOW.minus(Duration.ofDays(7)), 2);
    }

    @Test
    @DisplayName("저장소 예외는 밖으로 던지지 않는다 — 스케줄 스레드가 멈추지 않게")
    void swallowsStoreFailure() {
        OutboxStore failing = mock(OutboxStore.class);
        given(failing.deletePublishedBefore(any(Instant.class), anyInt())).willThrow(new IllegalStateException("db down MARKER"));
        ProcessedEventStore store = mock(ProcessedEventStore.class);

        assertThatCode(() -> new MessagingCleanup(failing, store, lockingTaskExecutor, properties(1000), CLOCK).cleanupOnce())
            .doesNotThrowAnyException();
        verify(store, never()).deleteProcessedBefore(any(Instant.class), anyInt());
    }

    @Test
    @DisplayName("락 — 릴레이에 있던 때의 이름(commerce-messaging-outbox-cleanup) · lock-at-most-for 를 그대로 쓴다")
    void usesExistingLockName() {
        new MessagingCleanup(null, processed, lockingTaskExecutor, properties(1000), CLOCK).cleanupOnce();

        assertThat(locks).singleElement().satisfies(lock -> {
            assertThat(lock.getName()).isEqualTo("commerce-messaging-outbox-cleanup");
            assertThat(lock.getLockAtMostFor()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    @DisplayName("저장소가 둘 다 없으면 만들지 않는다")
    void rejectsNoStore() {
        assertThatThrownBy(() -> new MessagingCleanup(null, null, lockingTaskExecutor, properties(1000), CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
