package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.KR;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.internationalQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderShippingAddressEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderStatusHistoryEntity;
import com.commercefoundry.domainlayer.order.application.exception.OrderSaveException;
import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOption;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOptions;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.domain.model.ShippingAddress;
import com.commercefoundry.domainlayer.order.domain.model.ShippingFeeQuote;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.ShippingScope;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 주문 영속성 어댑터를 실제 PostgreSQL 에 질의해 본다 — 파생 쿼리, 주문 PK · 재고 선점 유니크 중복, 배송지 1:1, check 제약.
 * 스키마는 DDL 스냅숏을 init script 로 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한다. Docker 없는 로컬은 건너뛰고 CI 는 실패한다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=orders",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({QuerydslConfigurer.class, OrderServiceFeaturesConfig.class, OrderRepositoryAdapter.class, OrderMapperImpl.class,
    OrderPersistenceAdapterTest.IdGeneratorConfig.class})
class OrderPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");

    private static final long MEMBER = 7L;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderRepositoryPort orderRepositoryPort;

    private final AtomicLong ids = new AtomicLong(10_000);

    @TestConfiguration
    static class IdGeneratorConfig {

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(1, 1);
        }
    }

    /** 국내 — 맞춤 제작 줄(입력 둘, 무게 · HS 있음)과 일반 줄(무게 · HS 없음). 무료배송 + 도서산간 3,000. */
    private static OrderQuote domesticQuote() {
        List<OrderQuoteLine> lines = List.of(
            OrderQuoteLine.of(9001L, 7001L, "저고리", "아이보리 / L", krw(39_000), 2,
                OrderCustomOptions.of(List.of(new OrderCustomOption("waist", "80"), new OrderCustomOption("chest", "96"))), true, 450, "0611200000"),
            OrderQuoteLine.of(9002L, 7002L, "치마", "", krw(4_000), 1, OrderCustomOptions.empty(), false, null, null));
        ShippingFeeQuote fee = new ShippingFeeQuote(krw(3_000), krw(3_000), true, krw(3_000), "제주", krw(50_000), 3L, ShippingScope.DOMESTIC, null, null, null);
        return new OrderQuote(CurrencyCode.KRW, lines, krw(82_000), fee, krw(85_000), KR);
    }

    private Order order(long orderId, long reservationId, OrderQuote quote, ShippingAddress address) {
        return Order.place(orderId, MEMBER, quote, ids::incrementAndGet, address, reservationId, Instant.parse("2026-10-18T09:30:00Z"));
    }

    private Order order(long orderId, long reservationId) {
        return order(orderId, reservationId, domesticQuote(), address(KR));
    }

    private void create(Order order) {
        orderRepositoryPort.insert(order, OrderStatusHistory.created(ids.incrementAndGet(), order));
        entityManager.clear();
    }

    @Test
    @DisplayName("주문 · 배송지 · 항목 · 입력 · 이력을 넣고, 회원 · 아이디로 스냅숏 그대로 다시 읽는다 — 다른 회원은 빈 값")
    void createsAndReads() {
        Order order = order(1L, 101L);
        create(order);

        Order read = orderRepositoryPort.findByIdAndMemberId(1L, MEMBER).orElseThrow();
        assertThat(read.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(read.version()).isZero();
        assertThat(read.createdAt()).isNotNull();
        assertThat(read.quote()).isEqualTo(order.quote());
        assertThat(read.itemIds()).isEqualTo(order.itemIds());
        assertThat(read.quote().lines().getFirst().customOptions().values()).extracting(OrderCustomOption::fieldKey).containsExactly("waist", "chest");
        assertThat(read.quote().lines().getFirst().customOptions().hash()).isEqualTo(order.quote().lines().getFirst().customOptions().hash());
        assertThat(read.quote().lines().get(1).weightGrams()).isNull();
        assertThat(read.shippingAddress()).isEqualTo(order.shippingAddress());
        assertThat(entityManager.find(OrderShippingAddressEntity.class, 1L).getRecipientPhone()).isEqualTo("010-0000-0001");
        assertThat(orderRepositoryPort.findByIdAndMemberId(1L, 8L)).isEmpty();
        assertThat(entityManager.find(OrderStatusHistoryEntity.class, ids.get())).isNotNull();
    }

    @Test
    @DisplayName("해외 주문은 우편번호 없이 저장되고 무게 요금 · 국가 그룹 · 총 무게를 스냅숏한다")
    void internationalWithoutPostalCode() {
        Order order = order(2L, 102L, internationalQuote(quoteLine(9001, 10_000, 3)), address("US"));
        create(order);

        Order read = orderRepositoryPort.findByIdAndMemberId(2L, MEMBER).orElseThrow();
        assertThat(read.shippingAddress().postalCode()).isNull();
        assertThat(read.quote().shippingFeeQuote()).isEqualTo(order.quote().shippingFeeQuote());
        assertThat(read.quote().shippingFeeQuote().totalWeightGrams()).isEqualTo(1_500);
    }

    @Test
    @DisplayName("같은 주문 아이디 · 같은 선점을 두 번 저장하면 ALREADY_SAVED — 다른 시도가 먼저 저장한 경우다")
    void sameOrderTwice() {
        create(order(1L, 101L));

        Order again = order(1L, 101L);
        assertThatThrownBy(() -> orderRepositoryPort.insert(again, OrderStatusHistory.created(ids.incrementAndGet(), again)))
            .isInstanceOfSatisfying(OrderSaveException.class, exception -> assertThat(exception.getReason()).isEqualTo(OrderSaveException.Reason.ALREADY_SAVED));
    }

    @Test
    @DisplayName("다른 주문이 같은 선점을 가리키면 재고 선점 유니크 키가 막는다 — ALREADY_SAVED")
    void sameReservationForOtherOrder() {
        create(order(1L, 101L));

        Order other = order(2L, 101L);
        assertThatThrownBy(() -> orderRepositoryPort.insert(other, OrderStatusHistory.created(ids.incrementAndGet(), other)))
            .isInstanceOfSatisfying(OrderSaveException.class, exception -> assertThat(exception.getReason()).isEqualTo(OrderSaveException.Reason.ALREADY_SAVED));
    }

    @Test
    @DisplayName("저장한 금액 컬럼이 결제 금액 식과 맞는다 (ck_orders_total_amount_consistent)")
    void amountColumns() {
        create(order(1L, 101L));
        OrderEntity saved = entityManager.find(OrderEntity.class, 1L);

        assertThat(saved.getTotalAmount()).isEqualTo(saved.getItemsAmount() + saved.getShippingFeeAmount());
        assertThat(saved.getShippingWeightFeeAmount()).isNull();
        assertThat(saved.getShippingRemoteAreaName()).isEqualTo("제주");
    }
}
