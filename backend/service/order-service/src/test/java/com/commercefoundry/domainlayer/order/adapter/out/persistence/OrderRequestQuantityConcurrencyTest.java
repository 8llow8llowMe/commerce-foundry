package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.application.mapper.OrderRequestMapperImpl;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRequestRepositoryPort;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequestItem;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.support.OrderFixtures;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.global.properties.OrderProperties;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.config.SnowflakeConfigurer;
import com.commercefoundry.persistence.config.SnowflakePropertiesConfig;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 진행 중 시도의 수량 계수(#436) — 한 회원이 새 키로 동시에 몰아도 상품 수량 상한을 넘겨 통과하지 않는다. 주문 생성 퍼사드의 "기록 삽입(커밋) → 자기 기록을 뺀 계수 →
 * 판정(넘치면 lease 종료)" 을 스레드마다 실제 PostgreSQL(READ COMMITTED)에서 되풀이한다. 계수는 실제 {@link OrderProcessor#heldQuantitiesByProduct} 다.
 *
 * <p>통과한 시도 중 마지막으로 센 시도는 다른 통과 시도의 기록(또는 그 주문)을 반드시 보므로 통과 합 ≤ 상한이다. 동시 몰림에서 서로를 세어 함께 거절될 수는 있다
 * (#231 과 같은 허용) — 그래서 통과 수 하한 대신 "거절한 시도는 남지 않는다(lease 종료)" 를 본다.
 *
 * <p>커넥션 풀은 스레드 수보다 크게 둔다. Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=orders",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "snowflake.datacenter-id=1", "snowflake.worker-id=5",
    "spring.datasource.hikari.maximum-pool-size=30"})
@Import({QuerydslConfigurer.class, OrderServiceFeaturesConfig.class, SnowflakePropertiesConfig.class, SnowflakeConfigurer.class, OrderRepositoryAdapter.class,
    OrderRequestRepositoryAdapter.class, OrderMapperImpl.class, OrderRequestMapperImpl.class, OrderProcessor.class,
    OrderRequestQuantityConcurrencyTest.Config.class})
class OrderRequestQuantityConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");

    private static final int THREADS = 20;
    private static final int QUANTITY = 4;
    private static final int LIMIT = 10;
    /** {@code OrderFixtures.quoteLine(101, ...)} 의 상품 아이디 (SKU + 7000). */
    private static final long PRODUCT = 7101;
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String HASH = "c".repeat(64);

    @Autowired private OrderRequestRepositoryPort requests;
    @Autowired private OrderRepositoryPort orders;
    @Autowired private OrderProcessor orderProcessor;
    @Autowired private SnowflakeIdGenerator ids;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    @DisplayName("한 회원 · 새 키 20 · 각 수량 4 · 상한 10 — 통과 합이 상한을 넘지 않고, 거절한 시도는 lease 를 끝내 남지 않는다")
    void concurrentNewKeysNeverPassOverTheLimit() throws Exception {
        verifyRace(false);
    }

    @Test
    @DisplayName("통과한 시도가 곧바로 완료(기록 COMPLETED + 주문 INSERT)돼도 (1) 기록 → (2) 주문 순서로 세어 빠지는 시도가 없다")
    void completionDuringTheRaceIsStillCounted() throws Exception {
        verifyRace(true);
    }

    /**
     * 경합 전에 같은 회원의 단건 시도가 혼자 통과하는지 먼저 본다(쥔 수량 0) — "전부 거절" 회귀를 잡는다. 그 4개가 남아 경합의 여유는 6 이다.
     * 끝난 뒤 쥔 수량(진행 중 + 결제 대기)은 통과한 시도의 수량과 정확히 같다 — 거절한 시도는 lease 를 끝내 남지 않는다.
     */
    private void verifyRace(boolean completeWhenPassed) throws Exception {
        long member = ids.generateId();
        OrderRequest sequential = attempt(member, completeWhenPassed);
        assertThat(sequential).as("혼자 시도하면 통과한다").isNotNull();

        int passed = race(member, completeWhenPassed);

        assertThat((1 + passed) * QUANTITY).isLessThanOrEqualTo(LIMIT);
        assertThat(orderProcessor.heldQuantitiesByProduct(member, Set.of(PRODUCT), 0)).isEqualTo(Map.of(PRODUCT, (1 + passed) * QUANTITY));
        if (passed == 0) {
            Instant now = Instant.now();
            assertThat(requests.sumInFlightQuantitiesByProduct(member, Set.of(PRODUCT), sequential.id(), now, now.minus(Duration.ofMinutes(30))))
                .as("모두 거절됐으면 경합이 남긴 진행 중 수량이 없다").isEmpty();
        }
    }

    private int race(long member, boolean completeWhenPassed) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return attempt(member, completeWhenPassed) != null;
                }));
            }
            start.countDown();
            int passed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    passed++;
                }
            }
            return passed;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 퍼사드의 순서 그대로 — 기록 삽입(항목 포함, 커밋) → 자기 기록을 뺀 계수 → 판정. 넘치면 lease 를 끝낸다(퍼사드의 catch). 통과하면 그 기록, 거절이면 null. */
    private OrderRequest attempt(long member, boolean completeWhenPassed) {
        OrderRequest own = requests.insertIfAbsent(
            OrderRequest.start(ids.generateId(), member, UUID.randomUUID(), HASH, ids.generateId(), Instant.now(), LEASE),
            List.of(new OrderRequestItem(PRODUCT, QUANTITY))).orElseThrow();
        int held = orderProcessor.heldQuantitiesByProduct(member, Set.of(PRODUCT), own.id()).getOrDefault(PRODUCT, 0);
        if (held + QUANTITY > LIMIT) {
            requests.endLease(own, Instant.now());
            return null;
        }
        if (completeWhenPassed) {
            Order order = Order.place(own.orderId(), member, OrderFixtures.domesticQuote(OrderFixtures.quoteLine(101, 1000, QUANTITY)), ids::generateId,
                OrderFixtures.address(OrderFixtures.KR), ids.generateId(), Instant.now().plusSeconds(600));
            TransactionTemplate tx = new TransactionTemplate(transactions);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> {
                requests.completeOwned(own, Instant.now(), Duration.ofSeconds(10));
                orders.insert(order, OrderStatusHistory.created(ids.generateId(), order));
            });
        }
        return own;
    }

    @TestConfiguration
    @EnableConfigurationProperties(OrderProperties.class)
    static class Config {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
