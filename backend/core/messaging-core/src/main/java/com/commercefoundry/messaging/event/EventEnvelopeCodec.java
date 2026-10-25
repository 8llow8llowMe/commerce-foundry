package com.commercefoundry.messaging.event;

import com.commercefoundry.messaging.MessagingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 봉투 ↔ JSON 문자열. 서비스의 Jackson 설정에 흔들리지 않게 자체 {@link JsonMapper}(Jackson 3 기본 — 모르는 필드 무시 · 시각 ISO-8601)를 쓴다.
 * Kafka 직렬화는 문자열({@code StringSerializer})이고 타입 헤더({@code __TypeId__})를 쓰지 않는다 — 서비스마다 클래스가 달라도 깨지지 않는다.
 *
 * <p>Kafka 헤더({@code cf-event-id} · {@code cf-event-type} · {@code cf-schema-version})에도 같은 값을 싣는다 — Kafka UI · DLT 도구가 본문을 열지 않고 고른다.
 */
public class EventEnvelopeCodec {

    public static final String HEADER_EVENT_ID = "cf-event-id";
    public static final String HEADER_EVENT_TYPE = "cf-event-type";
    public static final String HEADER_SCHEMA_VERSION = "cf-schema-version";

    /** 페이로드 필드 이름 금지 목록 (소문자 · 영숫자로 줄인 이름) — {@link #rejectForbiddenFields}. */
    private static final Set<String> FORBIDDEN_EXACT = Set.of("name", "fullname", "recipient", "receiver", "token", "tel", "mobile", "zipcode", "postalcode");
    private static final List<String> FORBIDDEN_SUFFIXES = List.of(
        "phone", "phonenumber", "email", "address", "password", "secret", "secretkey", "cardnumber", "paymentkey", "accesstoken", "refreshtoken",
        "recipientname", "receivername", "buyername", "customername", "membername"
    );

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final int maxPayloadBytes;

    /** @param maxPayloadBytes 봉투 JSON 한 건의 상한(UTF-8 바이트) */
    public EventEnvelopeCodec(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    /**
     * @throws MessagingException {@code PAYLOAD_TOO_LARGE} · {@code FORBIDDEN_FIELD} — 개발 중 시끄럽게 드러나도록 저장 전에 막는다
     */
    public String encode(EventEnvelope envelope) {
        rejectForbiddenFields(envelope.payload());
        String json = jsonMapper.writeValueAsString(envelope);
        if (json.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            throw new MessagingException(MessagingException.Reason.PAYLOAD_TOO_LARGE);
        }
        return json;
    }

    /** @throws EventEnvelopeException JSON 이 아니거나 필수 칸이 없다 — 재시도하지 않는다 */
    public EventEnvelope decode(String json) {
        if (json == null) {
            throw new EventEnvelopeException("빈 메시지입니다.");
        }
        try {
            return jsonMapper.readValue(json, EventEnvelope.class);
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            // 원인 메시지에 본문 조각이 들어갈 수 있어 타입만 남긴다
            throw new EventEnvelopeException("이벤트 봉투를 읽을 수 없습니다. cause=" + exception.getClass().getSimpleName());
        }
    }

    /** 페이로드를 업무 레코드로 바꾼다. 컨슈머는 {@link #requireVersion} 으로 버전을 먼저 본다. */
    public <T> T payload(EventEnvelope envelope, Class<T> type) {
        try {
            return jsonMapper.treeToValue(envelope.payload(), type);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new EventEnvelopeException("이벤트 페이로드를 읽을 수 없습니다. eventType=" + envelope.eventType()
                + ", cause=" + exception.getClass().getSimpleName());
        }
    }

    /** 컨슈머가 아는 버전보다 새 봉투면 DLT 로 보낸다(재시도 안 함). 낮은 버전은 모르는 필드 무시로 받는다. */
    public void requireVersion(EventEnvelope envelope, int supportedVersion) {
        if (envelope.schemaVersion() > supportedVersion) {
            throw new EventEnvelopeException("지원하지 않는 이벤트 버전입니다. eventType=" + envelope.eventType()
                + ", schemaVersion=" + envelope.schemaVersion() + ", supported=" + supportedVersion);
        }
    }

    /** 업무 레코드를 페이로드 트리로 바꾼다 (발행 쪽). */
    public JsonNode toTree(Object payload) {
        return jsonMapper.valueToTree(payload);
    }

    /**
     * 개인정보 · 비밀정보로 보이는 필드 이름을 막는다 (#304 A-5) — 이름을 소문자 · 영숫자로 줄여 {@link #FORBIDDEN_EXACT} 와 같거나
     * {@link #FORBIDDEN_SUFFIXES} 로 끝나면 실패다({@code productName} 은 통과, {@code shippingAddress} · {@code buyerEmail} 은 실패).
     * 완전한 방어가 아니라 실수를 잡는 장치다.
     */
    private static void rejectForbiddenFields(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            for (String field : node.propertyNames()) {
                String normalized = field.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (FORBIDDEN_EXACT.contains(normalized) || FORBIDDEN_SUFFIXES.stream().anyMatch(normalized::endsWith)) {
                    throw new MessagingException(MessagingException.Reason.FORBIDDEN_FIELD, "field=" + field);
                }
                rejectForbiddenFields(node.get(field));
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                rejectForbiddenFields(element);
            }
        }
    }
}
