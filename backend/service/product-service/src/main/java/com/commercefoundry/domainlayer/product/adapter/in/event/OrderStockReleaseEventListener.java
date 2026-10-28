package com.commercefoundry.domainlayer.product.adapter.in.event;

import com.commercefoundry.domainlayer.product.adapter.in.event.dto.OrderStockReleaseMessage;
import com.commercefoundry.domainlayer.product.application.command.OrderStockReleaseCommand;
import com.commercefoundry.domainlayer.product.application.model.StockReleaseOutcome;
import com.commercefoundry.domainlayer.product.application.port.in.StockEventUseCase;
import com.commercefoundry.domainlayer.product.domain.model.StockReleaseReason;
import com.commercefoundry.messaging.consume.IdempotentEventProcessor;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.messaging.properties.MessagingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 주문 만료 · 결제 전 취소 → 재고 선점 해제 컨슈머 ({@code order-flow-guide.md} §3-5). 그룹 {@code <ns>.product.order-stock-release} 하나가 두 토픽을 받는다.
 * 메시징이 꺼져 있으면({@code commerce.messaging.enabled=false}) 빈을 만들지 않는다 — 그동안은 만료 스윕 · internal 해제가 지금처럼 푼다.
 *
 * <p>처리 기록(inbox, {@code processed_event})과 선점 해제 · 재고 복원을 한 트랜잭션으로 묶는다({@link IdempotentEventProcessor#process}) — 같은 이벤트가
 * 다시 와도 재고는 한 번만 늘어난다. 선점 상태 전이 멱등(이미 RELEASED 면 그대로)이 2차 방어다.
 *
 * <p><b>internal 재조회를 하지 않는다</b> ({@code coding-conventions.md} §12 의 예외). 이 컨슈머가 하는 일은 RESERVED 선점을 푸는 것뿐이라 효과가
 * TTL 만료와 같고, 확정된 선점은 건드리지 않는다. 위조 이벤트의 최대 피해는 결제 대기 재고가 TTL 보다 일찍 풀리는 것이고, 그 주문의 확정은
 * {@code PRODUCT_025} → 재선점 경로로 흡수된다(§3-4). 서명 · ACL 은 결제 이벤트(#308) 전에 다시 본다.
 *
 * <p>결과별 처리 — 해제 · 이미 해제: 정상. 이미 확정: WARN 후 정상(기록을 남겨 다시 처리하지 않는다). 선점 없음 · 주문 불일치 · 읽을 수 없는 봉투 ·
 * 모르는 새 버전 · 아이디 형식 오류: 다시 받아도 같아 바로 DLT(기록 롤백). 동시 변경({@code PRODUCT_026}) · DB 오류: 재시도 뒤 DLT.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = MessagingProperties.ENABLED)
public class OrderStockReleaseEventListener {

    /** 컨슈머 그룹 목적 토막 — {@code <ns>.product.order-stock-release}. */
    public static final String CONSUMER_PURPOSE = "order-stock-release";

    private final StockEventUseCase stockEventUseCase;
    private final EventEnvelopeCodec eventEnvelopeCodec;
    private final IdempotentEventProcessor idempotentEventProcessor;
    private final TopicNames topicNames;

    @KafkaListener(
        id = "order-stock-release",
        topics = {
            "#{@topicNames.topic(T(com.commercefoundry.domainlayer.product.adapter.in.event.dto.OrderStockReleaseMessage).PRODUCER_SERVICE, "
                + "T(com.commercefoundry.domainlayer.product.adapter.in.event.dto.OrderStockReleaseMessage).EVENT_TYPE_EXPIRED)}",
            "#{@topicNames.topic(T(com.commercefoundry.domainlayer.product.adapter.in.event.dto.OrderStockReleaseMessage).PRODUCER_SERVICE, "
                + "T(com.commercefoundry.domainlayer.product.adapter.in.event.dto.OrderStockReleaseMessage).EVENT_TYPE_CANCELED)}"
        },
        groupId = "#{@topicNames.consumerGroup(T(com.commercefoundry.domainlayer.product.adapter.in.event.OrderStockReleaseEventListener).CONSUMER_PURPOSE)}"
    )
    public void onOrderStockRelease(String value) {
        EventEnvelope envelope = eventEnvelopeCodec.decode(value);
        eventEnvelopeCodec.requireVersion(envelope, OrderStockReleaseMessage.SCHEMA_VERSION);
        StockReleaseReason reason = reasonOf(envelope);
        OrderStockReleaseMessage message = eventEnvelopeCodec.payload(envelope, OrderStockReleaseMessage.class);
        if (message == null) {
            // "payload": null 은 봉투 검사를 지나 null 로 읽힌다 — NPE 로 재시도하지 않고 계약 위반으로 바로 DLT
            throw new NonRetryableEventException("페이로드가 없는 재고 해제 이벤트입니다. eventId=" + envelope.eventId());
        }
        OrderStockReleaseCommand command = new OrderStockReleaseCommand(parseId(message.reservationId(), envelope), parseId(message.orderId(), envelope), reason);
        idempotentEventProcessor.process(topicNames.consumerGroup(CONSUMER_PURPOSE), envelope, () -> release(envelope, command));
    }

    /** 멱등 처리기의 트랜잭션 안에서 돈다 — 여기서 던지면 선점 변경과 처리 기록이 함께 롤백된다. */
    private void release(EventEnvelope envelope, OrderStockReleaseCommand command) {
        StockReleaseOutcome outcome = stockEventUseCase.release(command);
        switch (outcome) {
            case RELEASED, ALREADY_RELEASED -> {
            }
            case CONFIRMED -> log.warn("order stock release ignored, reservation already confirmed. eventId={}, eventType={}, reservationId={}, orderId={}",
                envelope.eventId(), envelope.eventType(), command.reservationId(), command.orderId());
            case NOT_FOUND -> throw new NonRetryableEventException("없는 재고 선점의 해제 이벤트입니다. eventId=" + envelope.eventId());
            case ORDER_MISMATCH -> throw new NonRetryableEventException("재고 선점의 주문과 다른 주문의 해제 이벤트입니다. eventId=" + envelope.eventId());
        }
    }

    private static StockReleaseReason reasonOf(EventEnvelope envelope) {
        return switch (envelope.eventType()) {
            case OrderStockReleaseMessage.EVENT_TYPE_EXPIRED -> StockReleaseReason.ORDER_EXPIRED;
            case OrderStockReleaseMessage.EVENT_TYPE_CANCELED -> StockReleaseReason.ORDER_CANCELED;
            default -> throw new NonRetryableEventException("재고 해제 이벤트가 아닌 종류입니다. eventId=" + envelope.eventId());
        };
    }

    /** 아이디는 문자열로 온다(JS 정밀도). 빠졌거나 숫자가 아니면 계약 위반이다 — 메시지에 값을 싣지 않는다. */
    private static long parseId(String id, EventEnvelope envelope) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw new NonRetryableEventException("아이디 형식이 아닌 재고 해제 이벤트입니다. eventId=" + envelope.eventId());
        }
    }
}
