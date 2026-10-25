package com.commercefoundry.messaging.consume;

import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.EventEnvelopeException;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.messaging.properties.MessagingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * 컨슈머 오류 처리 (#304 A-6) — 일시 오류는 컨슈머 안에서 지수 백오프로 다시 시도하고, 상한을 넘거나 재시도해도 같은 오류면 {@code <topic>.dlt} 로 보낸다.
 * {@code @RetryableTopic} 은 쓰지 않는다 — 시도 수만큼 토픽이 늘고(공유 클러스터, 앱 토픽 생성 금지) 백오프 표현이 부족하다.
 *
 * <p>재시도하지 않는 것: 봉투를 읽을 수 없음({@link EventEnvelopeException} · 모르는 버전), {@link NonRetryableEventException},
 * 그리고 spring-kafka 기본 분류(역직렬화 · 메시지 변환 · 인자 해석 · ClassCast). DLT 적재는 ERROR 로그(토픽 · 파티션 · 오프셋 · eventId · 원인 타입 —
 * 본문 없음)와 지표 {@code commerce.messaging.consumer.dlt.total{topic}} 를 남긴다. DLT 레코드 헤더에는 예외 메시지 · 스택트레이스를 싣지 않는다. DLT 재처리는 사람이 한다(Kafka UI 재전송 · outbox 재발행).
 */
@Slf4j
public final class MessagingErrorHandlers {

    private MessagingErrorHandlers() {
    }

    /** @param meterRegistry 없으면 null */
    public static DefaultErrorHandler create(KafkaOperations<?, ?> kafka, MessagingProperties.Retry retry, MeterRegistry meterRegistry) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(kafka,
            (record, exception) -> new TopicPartition(TopicNames.deadLetterTopic(record.topic()), -1));
        // 예외 메시지 · 스택트레이스 헤더(값 · 키 둘 다)는 원문이 담겨 싣지 않는다 — DLT 는 Kafka UI 로 열람 · 7일 보존된다(#304 A-5). 예외 · 원인 클래스 이름만 남는다
        deadLetter.excludeHeader(HeaderNames.HeadersToAdd.EX_MSG, HeaderNames.HeadersToAdd.EX_STACKTRACE);
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.attempts() - 1);
        backOff.setInitialInterval(retry.initialBackoff().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxBackoff().toMillis());
        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            deadLetter.accept(record, exception);
            logDeadLetter(record, exception);
            if (meterRegistry != null) {
                meterRegistry.counter("commerce.messaging.consumer.dlt.total", "topic", record.topic()).increment();
            }
        }, backOff);
        handler.addNotRetryableExceptions(EventEnvelopeException.class, NonRetryableEventException.class);
        return handler;
    }

    private static void logDeadLetter(ConsumerRecord<?, ?> record, Exception exception) {
        Throwable cause = rootCause(exception);
        log.error("event sent to DLT. topic={}, partition={}, offset={}, eventId={}, cause={}",
            record.topic(), record.partition(), record.offset(), eventIdOf(record), cause.getClass().getSimpleName());
    }

    /** 헤더는 누구나 붙일 수 있다 — UUID 로 읽힐 때만 남겨 로그 위조(개행 · 임의 문자열)를 막는다. */
    static String eventIdOf(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(EventEnvelopeCodec.HEADER_EVENT_ID);
        if (header == null || header.value() == null) {
            return "-";
        }
        try {
            return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8)).toString();
        } catch (IllegalArgumentException exception) {
            return "invalid";
        }
    }

    /** 리스너 예외는 {@code ListenerExecutionFailedException} 으로 감싸여 온다 — 원인 타입을 남긴다. */
    private static Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
