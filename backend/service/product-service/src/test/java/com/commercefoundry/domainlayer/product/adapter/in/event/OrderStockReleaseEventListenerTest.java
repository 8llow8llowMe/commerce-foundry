package com.commercefoundry.domainlayer.product.adapter.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.product.adapter.in.event.dto.OrderStockReleaseMessage;
import com.commercefoundry.domainlayer.product.application.command.OrderStockReleaseCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.model.StockReleaseOutcome;
import com.commercefoundry.domainlayer.product.application.port.in.StockEventUseCase;
import com.commercefoundry.domainlayer.product.domain.model.StockReleaseReason;
import com.commercefoundry.messaging.consume.IdempotentEventProcessor;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.EventEnvelopeException;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.messaging.support.InMemoryProcessedEventStore;
import com.commercefoundry.messaging.support.NoOpTransactionManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 주문 만료 · 취소 이벤트 리스너 — 봉투 · 페이로드 해석, 사유 매핑, 계약 위반의 DLT 분류, 처리 기록(inbox)과 업무 처리의 한 트랜잭션.
 * 트랜잭션은 {@link NoOpTransactionManager} 로 경계 · 롤백 콜백만 흉내 낸다 — 실제 Kafka · DB 흐름은 {@code OrderStockReleaseFlowTest} 가 본다.
 */
class OrderStockReleaseEventListenerTest {

    /** 계약 정본 — payload JSON 은 이 문서 §3-5 의 json 블록이다. 문서의 JSON 을 바꾸면 이 테스트가 같이 본다. */
    private static final Path CONTRACT_DOCUMENT = Path.of("..", "..", "docs", "order-flow-guide.md");
    private static final String CONTRACT_SECTION = "### 3-5.";
    private static final Instant NOW = Instant.parse("2026-10-28T09:00:00Z");

    private final EventEnvelopeCodec codec = new EventEnvelopeCodec(65_536);
    private StockEventUseCase stockEventUseCase;
    private InMemoryProcessedEventStore processedEventStore;
    private OrderStockReleaseEventListener listener;

    @BeforeEach
    void setUp() {
        stockEventUseCase = Mockito.mock(StockEventUseCase.class);
        processedEventStore = new InMemoryProcessedEventStore();
        IdempotentEventProcessor processor = new IdempotentEventProcessor(
            () -> processedEventStore, new TransactionTemplate(new NoOpTransactionManager()), Clock.fixed(NOW, ZoneOffset.UTC)
        );
        listener = new OrderStockReleaseEventListener(stockEventUseCase, codec, processor, new TopicNames("commerce-foundry.test", "product"));
    }

    @Test
    @DisplayName("문서(§3-5)의 계약 JSON 을 그대로 받아 reservationId · orderId 로 ORDER_EXPIRED 해제를 부르고 처리 기록을 남긴다")
    void contractPayloadFromDocument() throws IOException {
        EventEnvelope envelope = codec.decode(envelopeJson(OrderStockReleaseMessage.EVENT_TYPE_EXPIRED, contractPayload()));
        OrderStockReleaseMessage contract = codec.payload(envelope, OrderStockReleaseMessage.class);
        given(stockEventUseCase.release(any())).willReturn(StockReleaseOutcome.RELEASED);

        listener.onOrderStockRelease(codec.encode(envelope));

        assertThat(contract.orderId()).isNotBlank();
        assertThat(contract.reservationId()).isNotBlank();
        assertThat(contract.reason()).isNotBlank();
        assertThat(releasedCommand()).isEqualTo(new OrderStockReleaseCommand(
            Long.parseLong(contract.reservationId()), Long.parseLong(contract.orderId()), StockReleaseReason.ORDER_EXPIRED
        ));
        assertThat(processedEventStore.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("order-canceled 는 ORDER_CANCELED 사유로 푼다 — 모르는 필드는 무시한다")
    void canceledEvent() {
        given(stockEventUseCase.release(any())).willReturn(StockReleaseOutcome.RELEASED);

        listener.onOrderStockRelease(codec.encode(envelope(OrderStockReleaseMessage.EVENT_TYPE_CANCELED, 1,
            codec.toTree(Map.of("orderId", "11", "reservationId", "22", "reason", "CUSTOMER_REQUEST", "futureField", 1)))));

        assertThat(releasedCommand()).isEqualTo(new OrderStockReleaseCommand(22L, 11L, StockReleaseReason.ORDER_CANCELED));
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 오면 해제는 한 번만 부르고 기록도 하나다")
    void duplicateIsSkipped() {
        given(stockEventUseCase.release(any())).willReturn(StockReleaseOutcome.RELEASED);
        String value = codec.encode(envelope(OrderStockReleaseMessage.EVENT_TYPE_EXPIRED, 1, payload("11", "22")));

        listener.onOrderStockRelease(value);
        listener.onOrderStockRelease(value);

        verify(stockEventUseCase, times(1)).release(any());
        assertThat(processedEventStore.size()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = StockReleaseOutcome.class, names = {"RELEASED", "ALREADY_RELEASED", "CONFIRMED"})
    @DisplayName("해제 · 이미 해제 · 이미 확정은 정상 처리다 — 예외 없이 기록을 남긴다(확정은 WARN 만)")
    void normalOutcomesAreRecorded(StockReleaseOutcome outcome) {
        given(stockEventUseCase.release(any())).willReturn(outcome);

        listener.onOrderStockRelease(codec.encode(envelope(OrderStockReleaseMessage.EVENT_TYPE_EXPIRED, 1, payload("11", "22"))));

        assertThat(processedEventStore.size()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = StockReleaseOutcome.class, names = {"NOT_FOUND", "ORDER_MISMATCH"})
    @DisplayName("선점 없음 · 주문 불일치는 계약 위반이라 재시도 없는 예외(DLT)이고, 처리 기록은 롤백된다 — 메시지에 페이로드 값을 싣지 않는다")
    void contractViolationsGoToDeadLetter(StockReleaseOutcome outcome) {
        given(stockEventUseCase.release(any())).willReturn(outcome);
        EventEnvelope envelope = envelope(OrderStockReleaseMessage.EVENT_TYPE_EXPIRED, 1, payload("987654321", "123456789"));

        assertThatThrownBy(() -> listener.onOrderStockRelease(codec.encode(envelope)))
            .isInstanceOf(NonRetryableEventException.class)
            .hasMessageContaining(envelope.eventId().toString())
            .hasMessageNotContaining("123456789")
            .hasMessageNotContaining("987654321");
        assertThat(processedEventStore.size()).isZero();
    }

    @Test
    @DisplayName("동시 변경(PRODUCT_026) 같은 업무 예외는 그대로 올린다 — 재시도 대상이고 처리 기록은 롤백된다")
    void businessErrorPropagates() {
        given(stockEventUseCase.release(any())).willThrow(new ProductException(ProductErrorCode.STOCK_RESERVATION_CONFLICT));
        String value = codec.encode(envelope(OrderStockReleaseMessage.EVENT_TYPE_EXPIRED, 1, payload("11", "22")));

        assertThatThrownBy(() -> listener.onOrderStockRelease(value)).isInstanceOf(ProductException.class);
        assertThat(processedEventStore.size()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", "1.5"})
    @DisplayName("아이디가 숫자 문자열이 아니면 해제를 부르지 않고 재시도 없는 예외(DLT)다")
    void malformedIdGoesToDeadLetter(String reservationId) {
        String value = codec.encode(envelope(OrderStockReleaseMessage.EVENT_TYPE_EXPIRED, 1, payload("11", reservationId)));

        assertThatThrownBy(() -> listener.onOrderStockRelease(value)).isInstanceOf(NonRetryableEventException.class);
        verify(stockEventUseCase, never()).release(any());
        assertThat(processedEventStore.size()).isZero();
    }

    @Test
    @DisplayName("아이디가 빠졌으면 재시도 없는 예외(DLT)다")
    void missingIdGoesToDeadLetter() {
        String value = codec.encode(envelope(OrderStockReleaseMessage.EVENT_TYPE_EXPIRED, 1, codec.toTree(Map.of("orderId", "11"))));

        assertThatThrownBy(() -> listener.onOrderStockRelease(value)).isInstanceOf(NonRetryableEventException.class);
        verify(stockEventUseCase, never()).release(any());
    }

    @ParameterizedTest
    @EnumSource(value = StockReleaseReason.class, names = {"EXPIRED", "RELEASED_BY_CALLER"})
    @DisplayName("이벤트 해제 명령은 주문 이벤트 사유(ORDER_EXPIRED · ORDER_CANCELED)만 받는다 — 스윕 · internal 해제 사유는 거절한다")
    void commandRejectsNonEventReasons(StockReleaseReason reason) {
        assertThatThrownBy(() -> new OrderStockReleaseCommand(22L, 11L, reason)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("\"payload\": null 이면 NPE(재시도)가 아니라 재시도 없는 예외(DLT)다 — 메시지에는 eventId 만")
    void nullPayloadGoesToDeadLetter() {
        UUID eventId = UUID.randomUUID();
        String value = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"order-expired\",\"schemaVersion\":1,\"occurredAt\":\"" + NOW
            + "\",\"producer\":\"order\",\"aggregateType\":\"order\",\"aggregateId\":\"1\",\"payload\":null}";

        assertThatThrownBy(() -> listener.onOrderStockRelease(value))
            .isInstanceOf(NonRetryableEventException.class)
            .hasMessageContaining(eventId.toString());
        verify(stockEventUseCase, never()).release(any());
        assertThat(processedEventStore.size()).isZero();
    }

    @Test
    @DisplayName("모르는 새 버전(schemaVersion 2)은 봉투 예외(재시도 없음)로 거른다")
    void newerVersionIsRejected() {
        String value = codec.encode(envelope(OrderStockReleaseMessage.EVENT_TYPE_EXPIRED, 2, payload("11", "22")));

        assertThatThrownBy(() -> listener.onOrderStockRelease(value)).isInstanceOf(EventEnvelopeException.class);
        verify(stockEventUseCase, never()).release(any());
    }

    @Test
    @DisplayName("해제 이벤트가 아닌 종류는 재시도 없는 예외(DLT)다")
    void unknownEventTypeIsRejected() {
        String value = codec.encode(envelope("order-paid", 1, payload("11", "22")));

        assertThatThrownBy(() -> listener.onOrderStockRelease(value)).isInstanceOf(NonRetryableEventException.class);
        verify(stockEventUseCase, never()).release(any());
    }

    @Test
    @DisplayName("읽을 수 없는 본문은 봉투 예외(재시도 없음)다")
    void unreadableValueIsRejected() {
        assertThatThrownBy(() -> listener.onOrderStockRelease("not-json")).isInstanceOf(EventEnvelopeException.class);
        verify(stockEventUseCase, never()).release(any());
    }

    private OrderStockReleaseCommand releasedCommand() {
        ArgumentCaptor<OrderStockReleaseCommand> captor = ArgumentCaptor.forClass(OrderStockReleaseCommand.class);
        verify(stockEventUseCase).release(captor.capture());
        return captor.getValue();
    }

    private JsonNode payload(String orderId, String reservationId) {
        return codec.toTree(new OrderStockReleaseMessage(orderId, reservationId, "PAYMENT_TIMEOUT"));
    }

    private static EventEnvelope envelope(String eventType, int schemaVersion, JsonNode payload) {
        return new EventEnvelope(UUID.randomUUID(), eventType, schemaVersion, NOW, "order", "order", "11", payload);
    }

    /** 발행 쪽이 낼 봉투 JSON — payload 자리에 문서의 JSON 을 글자 그대로 넣는다. */
    private static String envelopeJson(String eventType, String payloadJson) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + eventType + "\",\"schemaVersion\":1,\"occurredAt\":\"" + NOW
            + "\",\"producer\":\"order\",\"aggregateType\":\"order\",\"aggregateId\":\"1\",\"payload\":" + payloadJson + "}";
    }

    /** 문서 §3-5 다음의 첫 json 블록. */
    private static String contractPayload() throws IOException {
        List<String> lines = Files.readAllLines(CONTRACT_DOCUMENT, StandardCharsets.UTF_8);
        int section = indexOf(lines, CONTRACT_SECTION, 0);
        int start = indexOf(lines, "```json", section) + 1;
        int end = indexOf(lines, "```", start);
        return String.join("\n", lines.subList(start, end));
    }

    private static int indexOf(List<String> lines, String prefix, int from) {
        for (int index = from; index < lines.size(); index++) {
            if (lines.get(index).strip().startsWith(prefix)) {
                return index;
            }
        }
        throw new AssertionError(CONTRACT_DOCUMENT.toAbsolutePath() + " 에서 [" + prefix + "] 를 찾지 못했다 — 계약 절(§3-5)과 json 블록이 있어야 한다");
    }
}
