package com.commercefoundry.domainlayer.product.adapter.in.event;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.commercefoundry.domainlayer.product.adapter.in.event.dto.OrderStockReleaseMessage;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.SkuRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockReservationRepository;
import com.commercefoundry.domainlayer.product.application.command.StockReservationCommand;
import com.commercefoundry.domainlayer.product.application.port.in.StockInternalUseCase;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.StockReleaseReason;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import com.commercefoundry.global.messaging.ProcessedEventRepository;
import com.commercefoundry.messaging.config.MessagingBackgroundLifecycle;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

/**
 * 주문 만료 · 취소 이벤트 → 재고 선점 해제의 끝에서 끝 흐름 — 내장 Kafka 로 보낸 봉투를 리스너가 받아 Facade 트랜잭션 · H2 저장소로 처리한다
 * ({@code order-flow-guide.md} §3-5). 내장 Kafka(KRaft)라 Docker 없이 돈다 — operations {@code JobWorkerFlowTest} 와 같다.
 *
 * <p>해제 · 사유, 중복 이벤트의 재고 1회 복원, 확정된 선점 무시, 계약 위반 · 모르는 새 버전의 DLT 를 본다. 처리 기록 유니크 키의 동시 위반은
 * PostgreSQL 이 필요해 {@code JpaProcessedEventStoreTest} 가 본다. 컨텍스트를 테스트끼리 공유하므로 테스트마다 다른 상품 · SKU · 주문 아이디를 쓴다.
 *
 * <p>재시도 백오프를 기다림 상한({@link #TIMEOUT})보다 길게 둔다 — 계약 위반 · 모르는 새 버전이 잘못 재시도로 분류되면 DLT 도착이 상한을 넘겨 실패한다
 * ("재시도 없이 DLT" 를 증명). 정상 케이스는 예외가 없어 백오프를 타지 않는다.
 *
 * <p>내장 브로커의 {@code __consumer_offsets} 를 파티션 1개 · 복제 1 로 두고 첫 리밸런스 지연을 없앤다 — 기본값(50 파티션)이면 다른 테스트와 함께 돌 때
 * 코디네이터 로딩이 늦어 {@code NotCoordinatorException} 재합류가 이어지고 첫 소비가 기다림 상한을 넘긴 적이 있다.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:product-flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
        + "INIT=CREATE SCHEMA IF NOT EXISTS product",
    "commerce.messaging.enabled=true",
    "commerce.messaging.namespace=commerce-foundry.test",
    "commerce.messaging.retry.attempts=2",
    "commerce.messaging.retry.initial-backoff=30s",
    "commerce.messaging.retry.max-backoff=30s"
})
@ActiveProfiles("test")
@EmbeddedKafka(
    partitions = 1,
    bootstrapServersProperty = "spring.kafka.bootstrap-servers",
    topics = {
        OrderStockReleaseFlowTest.EXPIRED, OrderStockReleaseFlowTest.EXPIRED + ".dlt", OrderStockReleaseFlowTest.CANCELED, OrderStockReleaseFlowTest.CANCELED + ".dlt"
    },
    brokerProperties = {"offsets.topic.num.partitions=1", "offsets.topic.replication.factor=1", "group.initial.rebalance.delay.ms=0"}
)
class OrderStockReleaseFlowTest {

    static final String EXPIRED = "commerce-foundry.test.order.order-expired";
    static final String CANCELED = "commerce-foundry.test.order.order-canceled";
    private static final String GROUP = "commerce-foundry.test.product.order-stock-release";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final long STOCK = 10L;
    private static final int QUANTITY = 3;
    private static final AtomicLong IDS = new AtomicLong(700_000L);

    @Autowired
    private StockInternalUseCase stockInternalUseCase;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SkuRepository skuRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockReservationRepository stockReservationRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EventEnvelopeCodec codec;

    @Autowired
    private DeadLetters deadLetters;

    @Autowired
    private MessagingBackgroundLifecycle messagingBackgroundLifecycle;

    @Test
    @DisplayName("받기만 하는 서비스라 릴레이는 없고 처리 기록 정리만 걸린다(#378)")
    void runsInboxCleanupOnly() {
        assertThat(messagingBackgroundLifecycle.runsCleanup()).isTrue();
        assertThat(messagingBackgroundLifecycle.runsRelay()).isFalse();
        assertThat(messagingBackgroundLifecycle.isRunning()).isTrue();
    }

    @Test
    @DisplayName("order-expired 를 받으면 선점이 RELEASED(ORDER_EXPIRED) 가 되고 재고가 선점 수량만큼 한 번 돌아오며, 처리 기록이 한 행 남는다")
    void expiredReleases() {
        Seed seed = seed();
        EventEnvelope envelope = envelope(OrderStockReleaseMessage.EVENT_TYPE_EXPIRED, 1, seed.orderId(), seed.reservationId());
        assertThat(available(seed)).isEqualTo(STOCK - QUANTITY);

        send(EXPIRED, envelope);

        awaitProcessed(envelope);
        StockReservationEntity reservation = reservation(seed);
        assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.RELEASED);
        assertThat(reservation.getReleaseReason()).isEqualTo(StockReleaseReason.ORDER_EXPIRED);
        assertThat(available(seed)).isEqualTo(STOCK);
        assertThat(processedRows(envelope)).isEqualTo(1);
    }

    @Test
    @DisplayName("order-canceled 를 받으면 RELEASED(ORDER_CANCELED) 다")
    void canceledReleases() {
        Seed seed = seed();
        EventEnvelope envelope = envelope(OrderStockReleaseMessage.EVENT_TYPE_CANCELED, 1, seed.orderId(), seed.reservationId());

        send(CANCELED, envelope);

        awaitProcessed(envelope);
        assertThat(reservation(seed).getReleaseReason()).isEqualTo(StockReleaseReason.ORDER_CANCELED);
        assertThat(available(seed)).isEqualTo(STOCK);
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 와도 재고는 한 번만 돌아오고 처리 기록은 한 행이다")
    void duplicateRestoresOnce() {
        Seed seed = seed();
        EventEnvelope envelope = envelope(OrderStockReleaseMessage.EVENT_TYPE_EXPIRED, 1, seed.orderId(), seed.reservationId());

        send(EXPIRED, envelope);
        send(EXPIRED, envelope);
        // 파티션 하나 · 컨슈머 하나라 같은 토픽의 뒤 이벤트가 처리되면 앞의 중복도 처리가 끝났다
        Seed marker = seed();
        EventEnvelope markerEnvelope = envelope(OrderStockReleaseMessage.EVENT_TYPE_EXPIRED, 1, marker.orderId(), marker.reservationId());
        send(EXPIRED, markerEnvelope);
        awaitProcessed(markerEnvelope);

        assertThat(available(seed)).isEqualTo(STOCK);
        assertThat(processedRows(envelope)).isEqualTo(1);
        assertThat(deadLetters.contains(envelope)).isFalse();
    }

    @Test
    @DisplayName("결제가 먼저 끝나 확정된 선점은 그대로 CONFIRMED 이고 재고도 그대로다 — DLT 로 가지 않고 처리 기록을 남긴다")
    void confirmedIsIgnored() {
        Seed seed = seed();
        stockInternalUseCase.confirm(seed.reservationId());
        EventEnvelope envelope = envelope(OrderStockReleaseMessage.EVENT_TYPE_EXPIRED, 1, seed.orderId(), seed.reservationId());

        send(EXPIRED, envelope);

        awaitProcessed(envelope);
        assertThat(reservation(seed).getStatus()).isEqualTo(StockReservationStatus.CONFIRMED);
        assertThat(available(seed)).isEqualTo(STOCK - QUANTITY);
        assertThat(deadLetters.contains(envelope)).isFalse();
    }

    @Test
    @DisplayName("모르는 새 버전(schemaVersion 2)은 처리하지 않고 DLT 로 간다 — 선점 · 재고 · 처리 기록 그대로")
    void newerVersionGoesToDeadLetter() {
        Seed seed = seed();
        EventEnvelope envelope = envelope(OrderStockReleaseMessage.EVENT_TYPE_EXPIRED, 2, seed.orderId(), seed.reservationId());

        send(EXPIRED, envelope);

        awaitDeadLetter(envelope);
        assertUntouched(seed, envelope);
    }

    @Test
    @DisplayName("없는 선점 · 다른 주문의 선점을 가리키는 이벤트는 계약 위반이라 DLT 로 간다 — 선점 · 재고 · 처리 기록 그대로")
    void contractViolationsGoToDeadLetter() {
        Seed seed = seed();
        EventEnvelope missing = envelope(OrderStockReleaseMessage.EVENT_TYPE_EXPIRED, 1, seed.orderId(), IDS.incrementAndGet());
        EventEnvelope mismatch = envelope(OrderStockReleaseMessage.EVENT_TYPE_CANCELED, 1, seed.orderId() + 1, seed.reservationId());

        send(EXPIRED, missing);
        send(CANCELED, mismatch);

        awaitDeadLetter(missing);
        awaitDeadLetter(mismatch);
        assertUntouched(seed, missing);
        assertUntouched(seed, mismatch);
    }

    private void assertUntouched(Seed seed, EventEnvelope envelope) {
        assertThat(reservation(seed).getStatus()).isEqualTo(StockReservationStatus.RESERVED);
        assertThat(available(seed)).isEqualTo(STOCK - QUANTITY);
        assertThat(processedRows(envelope)).isZero();
    }

    /** 상품 · SKU · 재고(10개)를 만들고 3개를 선점한다. */
    private Seed seed() {
        long productId = IDS.incrementAndGet();
        long skuId = IDS.incrementAndGet();
        long orderId = IDS.incrementAndGet();
        productRepository.save(ProductEntity.builder()
            .id(productId).categoryId(10L).name("상품").saleStatus(SaleStatus.ON_SALE).visible(true).salePriceAmount(10_000L).salePriceCurrency(KRW)
            .build());
        skuRepository.save(SkuEntity.builder()
            .id(skuId).productId(productId).optionValueKey(String.valueOf(skuId)).additionalPriceAmount(0L).additionalPriceCurrency(KRW)
            .saleStatus(SaleStatus.ON_SALE)
            .build());
        stockRepository.save(StockEntity.builder().skuId(skuId).stockManaged(true).availableQuantity(STOCK).build());
        String reservationId = stockInternalUseCase.reserve(new StockReservationCommand(orderId, List.of(new StockReservationCommand.Item(skuId, QUANTITY))))
            .reservationId();
        return new Seed(skuId, orderId, Long.parseLong(reservationId));
    }

    private EventEnvelope envelope(String eventType, int schemaVersion, long orderId, long reservationId) {
        OrderStockReleaseMessage message = new OrderStockReleaseMessage(String.valueOf(orderId), String.valueOf(reservationId), "PAYMENT_TIMEOUT");
        return new EventEnvelope(UUID.randomUUID(), eventType, schemaVersion, Instant.now(), "order", "order", String.valueOf(orderId), codec.toTree(message));
    }

    private void send(String topic, EventEnvelope envelope) {
        kafkaTemplate.send(topic, envelope.aggregateId(), codec.encode(envelope));
    }

    private void awaitProcessed(EventEnvelope envelope) {
        await().atMost(TIMEOUT).until(() -> processedEventRepository.existsByConsumerGroupAndEventId(GROUP, envelope.eventId()));
    }

    private void awaitDeadLetter(EventEnvelope envelope) {
        await().atMost(TIMEOUT).until(() -> deadLetters.contains(envelope));
    }

    private long processedRows(EventEnvelope envelope) {
        return processedEventRepository.findAll().stream().filter(row -> row.getEventId().equals(envelope.eventId())).count();
    }

    private StockReservationEntity reservation(Seed seed) {
        return stockReservationRepository.findById(seed.reservationId()).orElseThrow();
    }

    private long available(Seed seed) {
        return stockRepository.findById(seed.skuId()).orElseThrow().getAvailableQuantity();
    }

    private record Seed(long skuId, long orderId, long reservationId) {
    }

    @TestConfiguration
    static class DeadLetterProbe {

        @Bean
        DeadLetters deadLetters() {
            return new DeadLetters();
        }
    }

    /** 두 토픽의 DLT 를 읽는다 — 그룹도 이 환경 namespace 로 시작해야 공통 리스너 검사를 통과한다. */
    static class DeadLetters {

        private final List<String> values = new CopyOnWriteArrayList<>();

        @KafkaListener(topics = {EXPIRED + ".dlt", CANCELED + ".dlt"}, groupId = "commerce-foundry.test.product.flow-test-dlt-probe")
        void on(String value) {
            values.add(value);
        }

        boolean contains(EventEnvelope envelope) {
            return values.stream().anyMatch(value -> value.contains(envelope.eventId().toString()));
        }
    }
}
