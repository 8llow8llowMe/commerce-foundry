package com.commercefoundry.domainlayer.product.application.service;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockRestorationResponse;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.SkuRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockReservationItemRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockReservationRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockRestorationItemRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockRestorationRepository;
import com.commercefoundry.domainlayer.product.application.command.StockReservationCommand;
import com.commercefoundry.domainlayer.product.application.command.StockRestorationCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.StockInternalUseCase;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.StockRestorationReason;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 결제 후 재고 복원의 동시성 — 실제 Facade 트랜잭션 경계(프록시 · READ COMMITTED · 선점 헤더 비관적 잠금)로 PostgreSQL 에서 본다.
 * 구성은 {@link StockReservationConcurrencyTest} 와 같다 — 스레드마다 유스케이스 호출 하나가 트랜잭션 하나다.
 * Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.datasource.hikari.maximum-pool-size=30"
})
class StockRestorationConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    private static final long PRODUCT_ID = 501L;
    private static final long SKU_A = 1001L;
    private static final int THREADS = 10;

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
    private StockReservationItemRepository stockReservationItemRepository;

    @Autowired
    private StockRestorationRepository stockRestorationRepository;

    @Autowired
    private StockRestorationItemRepository stockRestorationItemRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    @DisplayName("① 같은 클레임을 10 스레드가 동시에 복원해도 재고는 한 번만 늘고 복원 행은 하나다 — 잠금에서 줄을 서 모두 같은 복원으로 200 이다")
    void sameClaimRestoresOnce() throws Exception {
        seed(10L);
        long reservationId = confirmedReservation(40_000L, 4);
        assertThat(available(SKU_A)).isEqualTo(6L);

        List<Outcome> outcomes = runConcurrently(THREADS, index -> stockInternalUseCase.restore(command(50_000L, reservationId, 3)));

        assertThat(outcomes).as("헤더 잠금 덕분에 클레임 유니크 충돌(PRODUCT_030) 없이 모두 성공한다").allMatch(outcome -> outcome.errorCode() == null);
        assertThat(outcomes).extracting(outcome -> outcome.result().restorationId()).as("모두 같은 복원이다").containsOnly(outcomes.getFirst().result().restorationId());
        assertThat(available(SKU_A)).isEqualTo(9L);
        assertThat(stockRestorationRepository.count()).isEqualTo(1);
        assertThat(stockRestorationItemRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("② 같은 선점에 서로 다른 클레임이 합계가 확정 수량을 넘게 동시에 오면 복원 합은 확정 수량 이하이고, 재고 증가는 성공 합과 같다")
    void differentClaimsNeverExceed() throws Exception {
        seed(10L);
        long reservationId = confirmedReservation(40_000L, 5);
        assertThat(available(SKU_A)).isEqualTo(5L);

        // 10 클레임 × 1개 = 10 > 확정 5
        List<Outcome> outcomes = runConcurrently(THREADS, index -> stockInternalUseCase.restore(command(60_000L + index, reservationId, 1)));

        long succeeded = outcomes.stream().filter(outcome -> outcome.errorCode() == null).count();
        assertThat(outcomes).as("성공 또는 상한 초과만 나온다")
            .allMatch(outcome -> outcome.errorCode() == null || outcome.errorCode() == ProductErrorCode.STOCK_RESTORATION_QUANTITY_EXCEEDED);
        assertThat(succeeded).isEqualTo(5);
        assertThat(available(SKU_A)).isEqualTo(5L + succeeded);
        assertThat(stockRestorationRepository.count()).isEqualTo(succeeded);
    }

    /**
     * 같은 claimId 를 서로 다른 확정 선점 둘에 동시에 보내면 잠금 대상이 달라 줄을 서지 않는다 — 클레임 유니크 키가 하나만 통과시킨다.
     * 진 쪽은 대개 INSERT 에서 먼저 커밋된 쪽을 기다렸다가 {@code PRODUCT_030} 이다. 이긴 쪽이 진 쪽의 클레임 조회보다 먼저 커밋을 끝냈으면
     * 기존 복원을 보고 곧바로 {@code PRODUCT_029} 다 — 어느 쪽이든 재고는 한 번만 늘고, 진 쪽의 재시도는 {@code PRODUCT_029}(종결)다.
     */
    @Test
    @DisplayName("③ 같은 claimId 를 서로 다른 확정 선점 둘에 동시에 보내면 하나만 성공하고(진 쪽 PRODUCT_030) 재고는 한 번만 늘며, 진 쪽 재시도는 PRODUCT_029 다")
    void sameClaimOnDifferentReservations() throws Exception {
        seed(10L);
        long first = confirmedReservation(40_001L, 2);
        long second = confirmedReservation(40_002L, 2);
        assertThat(available(SKU_A)).isEqualTo(6L);
        List<Long> reservationIds = List.of(first, second);

        List<Outcome> outcomes = runConcurrently(2, index -> stockInternalUseCase.restore(command(70_000L, reservationIds.get(index), 1)));

        assertThat(outcomes).filteredOn(outcome -> outcome.errorCode() == null).hasSize(1);
        Outcome loser = outcomes.stream().filter(outcome -> outcome.errorCode() != null).findFirst().orElseThrow();
        assertThat(loser.errorCode()).isIn(ProductErrorCode.STOCK_RESTORATION_CONFLICT, ProductErrorCode.STOCK_RESTORATION_MISMATCH);
        assertThat(available(SKU_A)).as("재고는 한 번만 는다").isEqualTo(7L);
        assertThat(stockRestorationRepository.count()).isEqualTo(1);

        long loserReservationId = reservationIds.get(outcomes.indexOf(loser));
        Outcome retried = runConcurrently(1, index -> stockInternalUseCase.restore(command(70_000L, loserReservationId, 1))).getFirst();
        assertThat(retried.errorCode()).as("다른 선점으로 온 같은 클레임은 호출자 버그 — 종결").isEqualTo(ProductErrorCode.STOCK_RESTORATION_MISMATCH);
        assertThat(available(SKU_A)).isEqualTo(7L);
    }

    /**
     * 선점 헤더 잠금 대기 상한({@code jakarta.persistence.lock.timeout} 힌트)이 Hibernate 7 · PostgreSQL 에서 실제로 걸리는지 고정한다.
     * 힌트가 먹지 않으면 B 가 A 의 커밋까지 기다려 10초 안에 끝나지 않거나 성공해 실패한다.
     */
    @Test
    @DisplayName("④ 다른 트랜잭션이 선점 헤더 잠금을 쥐고 있으면 복원은 대기 상한 뒤 409 PRODUCT_031 이고 아무것도 바꾸지 않는다. 잠금이 풀리면 다시 시도해 성공한다")
    void lockTimeoutIsRetryableConflict() throws Exception {
        seed(10L);
        long reservationId = confirmedReservation(40_000L, 3);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Future<?> holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
            stockReservationRepository.findByIdForUpdate(reservationId).orElseThrow();
            locked.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Outcome blocked;
        long elapsedMillis;
        try {
            long startedAt = System.nanoTime();
            blocked = runConcurrently(1, index -> stockInternalUseCase.restore(command(80_000L, reservationId, 1)), 10).getFirst();
            elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        } finally {
            release.countDown();
        }
        holder.get(10, TimeUnit.SECONDS);

        assertThat(blocked.errorCode()).isEqualTo(ProductErrorCode.STOCK_RESERVATION_LOCK_TIMEOUT);
        assertThat(elapsedMillis).as("상한까지는 기다린다").isGreaterThanOrEqualTo(Long.parseLong(StockReservationRepository.LOCK_TIMEOUT_MILLIS) - 500L);
        assertThat(available(SKU_A)).isEqualTo(7L);
        assertThat(stockRestorationRepository.count()).isZero();

        Outcome retried = runConcurrently(1, index -> stockInternalUseCase.restore(command(80_000L, reservationId, 1))).getFirst();
        assertThat(retried.errorCode()).isNull();
        assertThat(available(SKU_A)).isEqualTo(8L);
    }

    /** SKU_A 와 재고 행을 깐다. */
    private void seed(long stock) {
        skuRepository.save(SkuEntity.builder()
            .id(SKU_A).productId(PRODUCT_ID).optionValueKey(String.valueOf(SKU_A)).additionalPriceAmount(0L).additionalPriceCurrency(KRW)
            .saleStatus(SaleStatus.ON_SALE)
            .build());
        stockRepository.save(StockEntity.builder().skuId(SKU_A).stockManaged(true).availableQuantity(stock).build());
    }

    /** 주문 하나로 SKU_A {@code quantity} 개를 선점 · 확정한 뒤 선점 아이디를 낸다. */
    private long confirmedReservation(long orderId, int quantity) {
        long reservationId = Long.parseLong(stockInternalUseCase.reserve(
            new StockReservationCommand(orderId, List.of(new StockReservationCommand.Item(SKU_A, quantity)))
        ).reservationId());
        stockInternalUseCase.confirm(reservationId);
        return reservationId;
    }

    /** 모든 스레드를 래치로 한꺼번에 출발시킨다. 30초 안에 끝나지 않으면(교착) 실패한다. */
    private List<Outcome> runConcurrently(int count, IndexedCall call) throws Exception {
        return runConcurrently(count, call, 30);
    }

    private List<Outcome> runConcurrently(int count, IndexedCall call, int timeoutSeconds) throws Exception {
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
            outcomes.add(future.get(timeoutSeconds, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    private long available(long skuId) {
        return stockRepository.findById(skuId).orElseThrow().getAvailableQuantity();
    }

    private void cleanUp() {
        stockRestorationItemRepository.deleteAllInBatch();
        stockRestorationRepository.deleteAllInBatch();
        stockReservationItemRepository.deleteAllInBatch();
        stockReservationRepository.deleteAllInBatch();
        stockRepository.deleteAllInBatch();
        skuRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
    }

    private static StockRestorationCommand command(long claimId, long reservationId, int quantity) {
        return new StockRestorationCommand(claimId, reservationId, StockRestorationReason.CANCELLATION, List.of(new StockRestorationCommand.Item(SKU_A, quantity)));
    }

    @FunctionalInterface
    private interface IndexedCall {

        StockRestorationResponse run(int index);
    }

    /** @param errorCode 성공이면 null */
    private record Outcome(StockRestorationResponse result, ProductErrorCode errorCode) {
    }
}
