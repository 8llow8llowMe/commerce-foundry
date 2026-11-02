package com.commercefoundry.domainlayer.payment.adapter.out.event;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.APPROVAL_KEY;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.MEMBER_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.ORDER_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.ORDER_NAME;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import com.commercefoundry.messaging.MessagingException;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.outbox.OutboxRow;
import com.commercefoundry.messaging.publish.EventPublisher;
import com.commercefoundry.messaging.support.InMemoryOutboxStore;
import com.commercefoundry.messaging.support.NoOpTransactionManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code payment-approved} v1 계약 ({@code payment-guide.md} §6) — 봉투(종류 · 버전 · 생산 서비스 · 키)와 페이로드 JSON 필드 · 값.
 * order-service 컨슈머(#308 PR-B)가 이 JSON 을 읽는다. 결제키 · 주문명 · 고객 멱등 키 같은 값이 실리지 않는지도 본다.
 */
class PaymentEventAdapterTest {

    private static final Instant NOW = Instant.parse("2026-10-21T01:00:04Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private InMemoryOutboxStore outbox;
    private PaymentEventAdapter adapter;
    private final TransactionTemplate transaction = new TransactionTemplate(new NoOpTransactionManager());

    @BeforeEach
    void setUp() {
        outbox = new InMemoryOutboxStore();
        EventPublisher publisher = new EventPublisher(() -> outbox, new EventEnvelopeCodec(65_536), PaymentApprovedMessage.PRODUCER_SERVICE,
            Clock.fixed(NOW, ZoneOffset.UTC));
        adapter = new PaymentEventAdapter(publisher);
    }

    @Test
    @DisplayName("봉투 — 종류 payment-approved · 버전 1 · 생산 payment · aggregate payment, 키(aggregateId)는 주문 아이디 문자열")
    void envelope() {
        transaction.executeWithoutResult(status -> adapter.approved(PaymentFixtures.done()));

        OutboxRow row = outbox.rows().getFirst();
        assertThat(outbox.rows()).hasSize(1);
        assertThat(row.aggregateType()).isEqualTo("payment");
        assertThat(row.aggregateId()).isEqualTo(Long.toString(ORDER_ID));
        assertThat(row.eventType()).isEqualTo("payment-approved");
        assertThat(row.schemaVersion()).isEqualTo(1);
        assertThat(row.availableAt()).isEqualTo(NOW);

        JsonNode envelope = JSON.readTree(row.envelopeJson());
        assertThat(envelope.path("eventType").asString()).isEqualTo("payment-approved");
        assertThat(envelope.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(envelope.path("producer").asString()).isEqualTo("payment");
        assertThat(envelope.path("aggregateType").asString()).isEqualTo("payment");
        assertThat(envelope.path("aggregateId").asString()).isEqualTo(Long.toString(ORDER_ID));
    }

    @Test
    @DisplayName("페이로드 — 아이디는 문자열, 금액은 최소 단위 정수 + 통화, 승인 시각 ISO-8601, 수단 코드. 필드는 이 7개뿐이다")
    void payloadContract() {
        transaction.executeWithoutResult(status -> adapter.approved(PaymentFixtures.done()));

        JsonNode payload = JSON.readTree(outbox.rows().getFirst().envelopeJson()).path("payload");
        List<String> fields = new ArrayList<>();
        payload.propertyNames().forEach(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("paymentId", "orderId", "memberId", "amount", "currency", "approvedAt", "method");
        assertThat(payload.path("paymentId").isString()).isTrue();
        assertThat(payload.path("paymentId").asString()).isEqualTo(Long.toString(PAYMENT_ID));
        assertThat(payload.path("orderId").asString()).isEqualTo(Long.toString(ORDER_ID));
        assertThat(payload.path("memberId").asString()).isEqualTo(Long.toString(MEMBER_ID));
        assertThat(payload.path("amount").isIntegralNumber()).isTrue();
        assertThat(payload.path("amount").asLong()).isEqualTo(129_000L);
        assertThat(payload.path("currency").asString()).isEqualTo("KRW");
        assertThat(payload.path("approvedAt").asString()).isEqualTo("2026-10-21T01:00:03Z");
        assertThat(payload.path("method").asString()).isEqualTo("EASY_PAY");
    }

    @Test
    @DisplayName("결제키 · 주문명 · 고객 멱등 키 · 간편결제 제공자 같은 값은 봉투 어디에도 없다")
    void noSensitiveValues() {
        transaction.executeWithoutResult(status -> adapter.approved(PaymentFixtures.done()));

        String json = outbox.rows().getFirst().envelopeJson();
        assertThat(json).doesNotContain(PAYMENT_KEY).doesNotContain(ORDER_NAME).doesNotContain(APPROVAL_KEY).doesNotContain("카카오페이");
    }

    @Test
    @DisplayName("DONE 이 아닌 결제는 승인 이벤트로 내지 않는다 — 호출 실수라 예외이고 outbox 에 남지 않는다")
    void rejectsNonDone() {
        for (Payment payment : List.of(PaymentFixtures.inProgress(), PaymentFixtures.unknown(), PaymentFixtures.lateApproved())) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> adapter.approved(payment))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(outbox.rows()).isEmpty();
    }

    @Test
    @DisplayName("트랜잭션 밖에서는 발행하지 않는다 — DONE 저장과 함께 커밋되지 않는 발행을 코드가 막는다")
    void requiresTransaction() {
        assertThatThrownBy(() -> adapter.approved(PaymentFixtures.done())).isInstanceOfSatisfying(MessagingException.class,
            exception -> assertThat(exception.getReason()).isEqualTo(MessagingException.Reason.NO_TRANSACTION));
        assertThat(outbox.rows()).isEmpty();
    }
}
