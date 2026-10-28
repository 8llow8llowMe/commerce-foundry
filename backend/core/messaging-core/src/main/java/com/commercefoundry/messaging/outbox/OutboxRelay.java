package com.commercefoundry.messaging.outbox;

import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.messaging.properties.MessagingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.springframework.kafka.core.KafkaOperations;

/**
 * outbox 폴링 릴레이 (#304 A-3) — 주기마다 보낼 행을 아이디 순으로 읽어 Kafka 로 보내고 표시한다.
 *
 * <ul>
 *   <li><b>단일 실행</b> — ShedLock 으로 한 인스턴스만 돈다. 한 인스턴스가 아이디 순으로 보내므로 같은 키(aggregateId)의 순서가 지켜진다.
 *       락이 만료돼 둘이 함께 돌아도 표시는 조건부 UPDATE 라 한 번이고, 남은 중복 발행은 컨슈머 멱등(inbox)이 흡수한다.</li>
 *   <li><b>트랜잭션 밖에서 보낸다</b> — 외부 I/O 를 트랜잭션 · 행 잠금 안에 두지 않는다. 한 건씩 응답({@code acks=all})을 기다린다.</li>
 *   <li><b>실패한 키는 그 주기 동안 멈춘다</b> — 같은 aggregateId 의 뒤 행을 먼저 보내 순서를 뒤집지 않고, 다른 키는 계속 보낸다.
 *       실패 행은 시도 수 · 실패 코드를 남기고 다음 주기에 다시 보낸다({@value #ALERT_ATTEMPTS}회부터 ERROR).</li>
 *   <li>로그 · 실패 코드에 봉투 · 예외 메시지를 싣지 않는다 — 아이디 · 이벤트 종류 · 예외 타입만.</li>
 * </ul>
 */
@Slf4j
public class OutboxRelay {

    static final String RELAY_LOCK = "commerce-messaging-outbox-relay";
    private static final int MAX_ERROR_CODE_LENGTH = 50;
    /** 이 시도 수부터 ERROR 로 남긴다. */
    static final int ALERT_ATTEMPTS = 10;

    private final OutboxStore outboxStore;
    private final KafkaOperations<String, String> kafka;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final TopicNames topicNames;
    private final EventEnvelopeCodec codec;
    private final MessagingProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    /**
     * @param meterRegistry 지표. 없으면 null
     */
    public OutboxRelay(
        OutboxStore outboxStore,
        KafkaOperations<String, String> kafka,
        LockingTaskExecutor lockingTaskExecutor,
        TopicNames topicNames,
        EventEnvelopeCodec codec,
        MessagingProperties properties,
        Clock clock,
        MeterRegistry meterRegistry
    ) {
        this.outboxStore = outboxStore;
        this.kafka = kafka;
        this.lockingTaskExecutor = lockingTaskExecutor;
        this.topicNames = topicNames;
        this.codec = codec;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /** 한 주기 — 락을 쥔 인스턴스만 보낸다. 예외를 밖으로 내지 않는다(스케줄 스레드가 멈추지 않게). */
    public void relayOnce() {
        MessagingProperties.Relay relay = properties.outbox().relay();
        try {
            lockingTaskExecutor.executeWithLock((Runnable) this::publishPending,
                new LockConfiguration(clock.instant(), RELAY_LOCK, relay.lockAtMostFor(), Duration.ZERO));
        } catch (RuntimeException exception) {
            // DB · 락 오류 상세가 원문으로 남지 않게 타입만
            log.warn("outbox relay run failed. error={}", exception.getClass().getSimpleName());
        }
    }

    /**
     * 락 없이 보낸다 — {@link #relayOnce} 가 락 안에서 부른다. 보낸 수를 돌려준다.
     *
     * <p>실패한 행의 aggregateId 는 이번 주기 동안 건너뛴다 — 같은 키의 순서는 지키고, 다른 키(다른 주문 · 다른 종류)는 계속 보낸다.
     * 토픽 하나가 없거나 행 하나가 다시 해도 실패해도 서비스의 모든 이벤트가 멈추지 않는다. 한 주기는 {@code lock-at-most-for} 의 절반 안에서 끝내
     * 락이 쥔 채로 만료되지 않게 한다(남은 행은 다음 주기).
     */
    int publishPending() {
        MessagingProperties.Relay relay = properties.outbox().relay();
        long deadline = System.nanoTime() + relay.lockAtMostFor().toNanos() / 2;
        Set<String> blockedAggregates = new HashSet<>();
        int published = 0;
        for (int batch = 0; batch < relay.maxBatchesPerRun(); batch++) {
            List<OutboxRow> rows = outboxStore.findPublishable(clock.instant(), relay.batchSize());
            int publishedInBatch = 0;
            for (OutboxRow row : rows) {
                if (blockedAggregates.contains(row.aggregateId())) {
                    continue;
                }
                if (System.nanoTime() - deadline > 0) {
                    return published;
                }
                SendOutcome result = send(row, relay.publishTimeout());
                if (result == SendOutcome.INTERRUPTED) {
                    return published;
                }
                if (result == SendOutcome.FAILED) {
                    blockedAggregates.add(row.aggregateId());
                } else {
                    publishedInBatch++;
                }
            }
            published += publishedInBatch;
            // 다 읽었거나, 이번 배치가 막힌 키뿐이라 다음 조회도 같은 행이 앞에 온다
            if (rows.size() < relay.batchSize() || publishedInBatch == 0) {
                break;
            }
        }
        return published;
    }

    private enum SendOutcome { PUBLISHED, FAILED, INTERRUPTED }

    private SendOutcome send(OutboxRow row, Duration timeout) {
        long startedAt = System.nanoTime();
        try {
            kafka.send(record(row)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            // 종료 중 끊김 — 발행 실패가 아니라 시도 수 · 실패 코드를 남기지 않는다(다음 기동이 다시 보낸다)
            Thread.currentThread().interrupt();
            return SendOutcome.INTERRUPTED;
        } catch (ExecutionException | TimeoutException | KafkaException exception) {
            fail(row, exception);
            return SendOutcome.FAILED;
        }
        boolean marked = outboxStore.markPublished(row.id(), clock.instant());
        if (meterRegistry != null) {
            if (marked) {
                meterRegistry.counter("commerce.messaging.outbox.published.total").increment();
                meterRegistry.timer("commerce.messaging.outbox.publish.latency").record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
            } else {
                meterRegistry.counter("commerce.messaging.outbox.publish.duplicate.total").increment();
            }
        }
        if (!marked) {
            log.debug("outbox row already marked by another run — duplicate publish absorbed by consumers. outboxId={}", row.id());
        }
        return SendOutcome.PUBLISHED;
    }

    private ProducerRecord<String, String> record(OutboxRow row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topicNames.topic(row.eventType()), row.aggregateId(), row.envelopeJson());
        String eventId = eventIdOf(row);
        if (eventId != null) {
            record.headers().add(EventEnvelopeCodec.HEADER_EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
        }
        record.headers().add(EventEnvelopeCodec.HEADER_EVENT_TYPE, row.eventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventEnvelopeCodec.HEADER_SCHEMA_VERSION, String.valueOf(row.schemaVersion()).getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /** 봉투의 eventId — 헤더용. 봉투를 읽지 못하면 헤더를 생략한다(본문은 그대로 보낸다 — 컨슈머가 DLT 로 보낸다). */
    private String eventIdOf(OutboxRow row) {
        try {
            return codec.decode(row.envelopeJson()).eventId().toString();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void fail(OutboxRow row, Exception exception) {
        Throwable cause = exception instanceof ExecutionException && exception.getCause() != null ? exception.getCause() : exception;
        String errorCode = cause.getClass().getSimpleName();
        if (errorCode.length() > MAX_ERROR_CODE_LENGTH) {
            errorCode = errorCode.substring(0, MAX_ERROR_CODE_LENGTH);
        }
        int attempts = row.publishAttempts() + 1;
        if (attempts >= ALERT_ATTEMPTS) {
            // 토픽 누락 · 다시 해도 같은 실패 — 이 키의 이벤트가 계속 묶여 있다. 사람이 볼 신호다
            log.error("outbox row keeps failing, aggregate is blocked. outboxId={}, eventType={}, attempts={}, error={}",
                row.id(), row.eventType(), attempts, errorCode);
        } else {
            log.warn("outbox publish failed, will retry next run. outboxId={}, eventType={}, attempts={}, error={}",
                row.id(), row.eventType(), attempts, errorCode);
        }
        try {
            outboxStore.markFailed(row.id(), errorCode);
        } catch (RuntimeException markFailure) {
            log.warn("outbox failure not recorded. outboxId={}, error={}", row.id(), markFailure.getClass().getSimpleName());
        }
        if (meterRegistry != null) {
            meterRegistry.counter("commerce.messaging.outbox.publish.failed.total", "error", errorCode).increment();
        }
    }
}
