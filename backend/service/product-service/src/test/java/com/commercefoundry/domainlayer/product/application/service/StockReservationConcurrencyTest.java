package com.commercefoundry.domainlayer.product.application.service;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.SkuRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockReservationItemRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockReservationRepository;
import com.commercefoundry.domainlayer.product.application.command.StockReservationCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.StockInternalUseCase;
import com.commercefoundry.domainlayer.product.application.port.in.StockSchedulerUseCase;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 재고 선점 · 해제 · 만료의 동시성 — 실제 Facade 트랜잭션 경계(프록시 · READ COMMITTED · 행 잠금)로 PostgreSQL 에서 본다.
 * 테스트 메서드는 트랜잭션 밖이다({@code @SpringBootTest} 기본) — 스레드마다 유스케이스 호출 하나가 트랜잭션 하나다.
 *
 * <p>스키마는 DDL 스냅숏 + {@code ddl-auto=validate} 다. 커넥션 풀은 동시 스레드 수보다 크게 둔다 — 풀이 작으면 경합이 풀 대기로 바뀌어
 * 행 잠금 경합을 보지 못한다. Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.datasource.hikari.maximum-pool-size=30"
})
class StockReservationConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    private static final long PRODUCT_ID = 501L;
    private static final long SKU_A = 1001L;
    private static final long SKU_B = 1002L;
    private static final int THREADS = 20;

    @Autowired
    private StockInternalUseCase stockInternalUseCase;

    @Autowired
    private StockSchedulerUseCase stockSchedulerUseCase;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SkuRepository skuRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockReservationRepository stockReservationRepository;

    @Autowired
    private StockReservationItemRepository stockReservationItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        cleanUp();
        productRepository.save(ProductEntity.builder()
            .id(PRODUCT_ID).categoryId(10L).name("상품").saleStatus(SaleStatus.ON_SALE).visible(true).salePriceAmount(10_000L).salePriceCurrency(KRW)
            .build());
        executor = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        cleanUp();
    }

    @Test
    @DisplayName("① 판매 가능 5개에 20개 주문이 동시에 1개씩 선점하면 정확히 5건만 성공하고 남은 수량은 0 이다 — 초과 판매 없음")
    void noOversell() throws Exception {
        seed(SKU_A, 5L);

        List<Outcome> outcomes = runConcurrently(THREADS, index -> stockInternalUseCase.reserve(command(10_000L + index, SKU_A, 1)));

        assertThat(count(outcomes, null)).isEqualTo(5);
        assertThat(count(outcomes, ProductErrorCode.STOCK_INSUFFICIENT)).isEqualTo(THREADS - 5);
        assertThat(available(SKU_A)).isZero();
        assertThat(stockReservationRepository.count()).as("실패한 선점은 롤백돼 행이 남지 않는다").isEqualTo(5);
    }

    @Test
    @DisplayName("② SKU 2개를 서로 다른 순서로 담은 주문 20개가 동시에 와도 교착 없이 끝나고, 차감 합이 성공 수량 합과 같다")
    void noDeadlock() throws Exception {
        seed(SKU_A, 15L);
        seed(SKU_B, 1_000L);

        List<Outcome> outcomes = runConcurrently(THREADS, index -> stockInternalUseCase.reserve(index % 2 == 0
            ? new StockReservationCommand(20_000L + index, List.of(item(SKU_A, 1), item(SKU_B, 2)))
            : new StockReservationCommand(20_000L + index, List.of(item(SKU_B, 2), item(SKU_A, 1)))));

        long succeeded = count(outcomes, null);
        assertThat(outcomes).as("교착 · 그 밖의 오류 없이 성공 또는 재고 부족만 나온다")
            .allMatch(outcome -> outcome.errorCode() == null || outcome.errorCode() == ProductErrorCode.STOCK_INSUFFICIENT);
        assertThat(succeeded).isEqualTo(15);
        assertThat(available(SKU_A)).isEqualTo(15L - succeeded);
        assertThat(available(SKU_B)).as("A 가 부족해 실패한 주문의 B 차감도 롤백된다").isEqualTo(1_000L - succeeded * 2);
    }

    @Test
    @DisplayName("③ 같은 선점을 10 스레드가 동시에 해제해도 재고는 한 번만 되돌아온다")
    void releaseRestoresOnce() throws Exception {
        seed(SKU_A, 10L);
        long reservationId = Long.parseLong(stockInternalUseCase.reserve(command(30_000L, SKU_A, 3)).reservationId());
        assertThat(available(SKU_A)).isEqualTo(7L);

        List<Outcome> outcomes = runConcurrently(10, index -> stockInternalUseCase.release(reservationId));

        assertThat(outcomes).allMatch(outcome -> outcome.errorCode() == null || outcome.errorCode() == ProductErrorCode.STOCK_RESERVATION_CONFLICT);
        assertThat(available(SKU_A)).isEqualTo(10L);
        assertThat(stockReservationRepository.findById(reservationId).orElseThrow().getStatus()).isEqualTo(StockReservationStatus.RELEASED);
    }

    @Test
    @DisplayName("④ 만료 시각이 지난 선점에 확정과 만료가 동시에 오면 하나만 상태를 바꾸고, 재고는 이긴 쪽과 맞는다")
    void confirmVersusExpire() throws Exception {
        seed(SKU_A, 100L);
        for (int round = 0; round < 10; round++) {
            long reservationId = Long.parseLong(stockInternalUseCase.reserve(command(40_000L + round, SKU_A, 3)).reservationId());
            jdbcTemplate.update("update product.stock_reservation set expires_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(60)), reservationId);
            long before = available(SKU_A);

            List<Outcome> outcomes = runConcurrently(2, index -> index == 0
                ? stockInternalUseCase.confirm(reservationId)
                : stockSchedulerUseCase.expire(reservationId));

            StockReservationStatus status = stockReservationRepository.findById(reservationId).orElseThrow().getStatus();
            assertThat(status).isIn(StockReservationStatus.CONFIRMED, StockReservationStatus.RELEASED);
            if (status == StockReservationStatus.CONFIRMED) {
                assertThat(available(SKU_A)).as("확정이 이겼으면 재고는 그대로다").isEqualTo(before);
                assertThat(outcomes.get(1).result()).as("만료는 바꾸지 못했다").isIn(false, null);
            } else {
                assertThat(available(SKU_A)).as("만료가 이겼으면 재고가 돌아온다").isEqualTo(before + 3);
                assertThat(outcomes.get(0).errorCode()).isIn(ProductErrorCode.STOCK_RESERVATION_RELEASED, ProductErrorCode.STOCK_RESERVATION_CONFLICT);
            }
        }
    }

    /** 모든 스레드를 래치로 한꺼번에 출발시킨다. 30초 안에 끝나지 않으면(교착) 실패한다. */
    private List<Outcome> runConcurrently(int count, IndexedCall call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int current = index;
            Callable<Outcome> task = () -> {
                start.await();
                try {
                    return new Outcome(call.run(current), null);
                } catch (ProductException exception) {
                    return new Outcome(null, exception.getErrorCode());
                }
            };
            futures.add(executor.submit(task));
        }
        start.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    private void seed(long skuId, long availableQuantity) {
        skuRepository.save(SkuEntity.builder()
            .id(skuId).productId(PRODUCT_ID).optionValueKey(String.valueOf(skuId)).additionalPriceAmount(0L).additionalPriceCurrency(KRW)
            .saleStatus(SaleStatus.ON_SALE)
            .build());
        stockRepository.save(StockEntity.builder().skuId(skuId).stockManaged(true).availableQuantity(availableQuantity).build());
    }

    private long available(long skuId) {
        return stockRepository.findById(skuId).orElseThrow().getAvailableQuantity();
    }

    private void cleanUp() {
        stockReservationItemRepository.deleteAllInBatch();
        stockReservationRepository.deleteAllInBatch();
        stockRepository.deleteAllInBatch();
        skuRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
    }

    private static long count(List<Outcome> outcomes, ProductErrorCode errorCode) {
        return outcomes.stream().filter(outcome -> outcome.errorCode() == errorCode).count();
    }

    private static StockReservationCommand command(long orderId, long skuId, int quantity) {
        return new StockReservationCommand(orderId, List.of(item(skuId, quantity)));
    }

    private static StockReservationCommand.Item item(long skuId, int quantity) {
        return new StockReservationCommand.Item(skuId, quantity);
    }

    @FunctionalInterface
    private interface IndexedCall {

        Object run(int index);
    }

    /** @param errorCode 성공이면 null */
    private record Outcome(Object result, ProductErrorCode errorCode) {
    }
}
