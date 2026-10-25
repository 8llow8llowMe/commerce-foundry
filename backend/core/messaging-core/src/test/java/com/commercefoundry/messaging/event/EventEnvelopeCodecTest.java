package com.commercefoundry.messaging.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.messaging.MessagingException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 봉투 직렬화 · 버전 · 크기 상한, 이름 규칙. */
class EventEnvelopeCodecTest {

    record SampleCreated(String sampleId, long amount, String currency) {
    }

    private final EventEnvelopeCodec codec = new EventEnvelopeCodec(2048);

    private EventEnvelope envelope(int version) {
        return new EventEnvelope(UUID.fromString("00000000-0000-0000-0000-000000000001"), "sample-created", version, Instant.parse("2026-10-25T03:00:00Z"),
            "messaging-test", "sample", "42", codec.toTree(new SampleCreated("42", 39000L, "KRW")));
    }

    @Test
    @DisplayName("봉투는 JSON 으로 오가고 시각은 ISO-8601, 페이로드는 업무 레코드로 읽힌다")
    void roundTrip() {
        String json = codec.encode(envelope(1));

        assertThat(json).contains("\"occurredAt\":\"2026-10-25T03:00:00Z\"", "\"eventType\":\"sample-created\"");
        EventEnvelope decoded = codec.decode(json);
        assertThat(decoded.eventId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(codec.payload(decoded, SampleCreated.class)).isEqualTo(new SampleCreated("42", 39000L, "KRW"));
    }

    @Test
    @DisplayName("모르는 필드는 무시한다 — 생산자가 필드를 더해도 소비자가 깨지지 않는다")
    void ignoresUnknownFields() {
        String json = codec.encode(envelope(1)).replace("\"producer\"", "\"addedLater\":1,\"producer\"");

        assertThat(codec.decode(json).aggregateId()).isEqualTo("42");
    }

    @Test
    @DisplayName("읽을 수 없는 봉투 · 모르는 새 버전은 재시도하지 않는 예외이고, 메시지에 본문이 없다")
    void rejectsUnreadable() {
        assertThatThrownBy(() -> codec.decode("{\"secret\":\"MARKER\""))
            .isInstanceOf(EventEnvelopeException.class).message().doesNotContain("MARKER");
        assertThatThrownBy(() -> codec.decode("{\"eventType\":\"x\"}")).isInstanceOf(EventEnvelopeException.class);
        assertThatThrownBy(() -> codec.requireVersion(envelope(2), 1)).isInstanceOf(EventEnvelopeException.class);
        codec.requireVersion(envelope(1), 2);
    }

    @Test
    @DisplayName("상한을 넘는 봉투는 저장 전에 막는다")
    void rejectsLarge() {
        EventEnvelope large = new EventEnvelope(UUID.randomUUID(), "sample-created", 1, Instant.now(), "messaging-test", "sample", "42",
            codec.toTree(new SampleCreated("x".repeat(3000), 1L, "KRW")));

        assertThatThrownBy(() -> codec.encode(large))
            .isInstanceOfSatisfying(MessagingException.class, exception -> assertThat(exception.getReason()).isEqualTo(MessagingException.Reason.PAYLOAD_TOO_LARGE));
    }

    @Test
    @DisplayName("이름 규칙 — 토픽 <ns>.<서비스>.<이벤트>, DLT <topic>.dlt, 그룹 <ns>.<서비스>.<목적>")
    void topicNames() {
        TopicNames names = new TopicNames("commerce-foundry.dev", "order-service");

        assertThat(names.topic("order-expired")).isEqualTo("commerce-foundry.dev.order-service.order-expired");
        assertThat(names.topic("payment-service", "payment-approved")).isEqualTo("commerce-foundry.dev.payment-service.payment-approved");
        assertThat(TopicNames.deadLetterTopic(names.topic("order-expired"))).isEqualTo("commerce-foundry.dev.order-service.order-expired.dlt");
        assertThat(names.consumerGroup("payment-approved")).isEqualTo("commerce-foundry.dev.order-service.payment-approved");
        assertThat(names.qualified("payment-service.payment-approved")).isEqualTo("commerce-foundry.dev.payment-service.payment-approved");
    }
}
