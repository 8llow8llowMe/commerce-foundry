package com.commercefoundry.domainlayer.skucost.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercefoundry.domainlayer.product.application.service.processor.SkuLookupProcessor;
import com.commercefoundry.domainlayer.skucost.application.command.SkuCostCreateCommand;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostErrorCode;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostException;
import com.commercefoundry.domainlayer.skucost.application.port.in.SkuCostAdminUseCase;
import com.commercefoundry.domainlayer.skucost.application.service.processor.SkuCostAppendProcessor;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * SKU 원가 추가의 동시성 — 실제 Facade 트랜잭션(프록시 · 시간 상한 · SKU 행 잠금)으로 PostgreSQL 에서 본다. 잠금 대기 취소는 엔진에 기대므로 H2 로는 볼 수 없다.
 * 테스트 메서드는 트랜잭션 밖이다({@code @SpringBootTest} 기본). Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class SkuCostAdminConcurrencyTest {

    private static final long SKU_ID = 9001L;
    private static final Instant EFFECTIVE_FROM = Instant.parse("2026-10-01T00:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @Autowired
    private SkuCostAdminUseCase skuCostAdminUseCase;

    @Autowired
    private SkuLookupProcessor skuLookupProcessor;

    @Autowired
    private SkuCostAppendProcessor skuCostAppendProcessor;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("""
            insert into product.sku (id, created_at, updated_at, additional_price_amount, additional_price_currency, option_value_key, product_id, sale_status)
            values (?, now(), now(), 0, 'KRW', '', 1, 'ON_SALE')
            """, SKU_ID);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    @DisplayName("같은 SKU · 유형 · 시작으로 동시에 추가하면 하나만 저장되고 다른 하나는 409(SKU_COST_006 또는 004)다 — 과거 시작이라 늦게 판정한 쪽은 소급 거절")
    void concurrentAppendsKeepOneRow() throws Exception {
        // 첫 질의 준비(쿼리 해석 · 커넥션)가 경합 중에 일어나 먼저 잠근 쪽이 잠금 대기 상한(2초)을 넘기도록 붙들지 않게 미리 한 번 읽는다
        skuCostAdminUseCase.getCurrentCosts(SKU_ID);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Throwable> append = () -> {
                start.await(10, TimeUnit.SECONDS);
                return catchThrowable(() -> skuCostAdminUseCase.createCost(command(EFFECTIVE_FROM)));
            };
            List<Future<Throwable>> futures = new ArrayList<>();
            futures.add(executor.submit(append));
            futures.add(executor.submit(append));
            start.countDown();

            List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                Throwable failure = future.get(30, TimeUnit.SECONDS);
                if (failure != null) {
                    failures.add(failure);
                }
            }

            assertThat(failures).singleElement().isInstanceOf(SkuCostException.class)
                .extracting(failure -> ((SkuCostException) failure).getErrorCode())
                .isIn(SkuCostErrorCode.RETROACTIVE_NOT_ALLOWED, SkuCostErrorCode.COST_CONFLICT);
            assertThat(jdbcTemplate.queryForObject("select count(*) from product.sku_cost where sku_id = ?", Long.class, SKU_ID)).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("다른 트랜잭션이 SKU 행을 잠그고 있으면 시간 상한 안에 409 · SKU_COST_005 로 끊기고 아무것도 쓰지 않는다")
    void lockWaitIsBounded() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = holder.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                skuLookupProcessor.requireForUpdate(SKU_ID);
                locked.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            Throwable failure = catchThrowable(() -> skuCostAdminUseCase.createCost(command(EFFECTIVE_FROM)));
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            release.countDown();
            holding.get(30, TimeUnit.SECONDS);

            assertThat(failure).isInstanceOf(SkuCostException.class)
                .extracting(exception -> ((SkuCostException) exception).getErrorCode())
                .isEqualTo(SkuCostErrorCode.SKU_COST_BUSY);
            assertThat(waitedMillis).isLessThan(10_000L);
            assertThat(jdbcTemplate.queryForObject("select count(*) from product.sku_cost", Long.class)).isZero();
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
    }

    @Test
    @DisplayName("잠금 · 추가 Processor 메서드를 트랜잭션 밖에서 부르면 바로 실패한다 (MANDATORY)")
    void writeOutsideTransactionFails() {
        assertThat(catchThrowable(() -> skuLookupProcessor.requireForUpdate(SKU_ID))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(catchThrowable(() -> skuCostAppendProcessor.append(command(EFFECTIVE_FROM)))).isInstanceOf(IllegalTransactionStateException.class);
    }

    private static SkuCostCreateCommand command(Instant effectiveFrom) {
        return SkuCostCreateCommand.builder()
            .skuId(SKU_ID)
            .costType(SkuCostType.GOODS)
            .amount(Money.of(12_000, CurrencyCode.KRW))
            .effectiveFrom(effectiveFrom)
            .reason("동시 입력")
            .adminMemberId(7L)
            .build();
    }

    private void cleanUp() {
        jdbcTemplate.update("delete from product.sku_cost");
        jdbcTemplate.update("delete from product.sku where id = ?", SKU_ID);
    }
}
