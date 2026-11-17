package com.commercefoundry;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.commercefoundry.domainlayer.product.application.command.ProductPricingUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Mode;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Outcome;
import com.commercefoundry.domainlayer.product.application.service.processor.LegacyPriceWriteProcessor;
import com.commercefoundry.domainlayer.product.domain.model.ProductCurrencyPrice;
import com.commercefoundry.domainlayer.product.domain.model.SkuCurrencyPrice;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 운영과 같은 PostgreSQL 17 스키마 · 행 잠금 · 제약 이름에서 backfill 계약과 동시 writer 경합을 검증한다. */
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class PriceBackfillPostgresIntegrationTest extends PriceBackfillIntegrationTest {
    @MockitoSpyBean LegacyPriceWriteProcessor coordinator;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @AfterEach
    void resetSpy() {
        reset(coordinator);
    }

    @Test
    void duplicateBaseRowInsertFromAnotherTransactionIsConflictNotServerError() {
        tx.executeWithoutResult(s -> prices.insertAll(List.of(new ProductCurrencyPrice(950020, KRW_PRODUCT, Money.of(1000, KRW), Money.of(1500, KRW))), List.of()));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> prices.insertAll(
            List.of(new ProductCurrencyPrice(950021, KRW_PRODUCT, Money.of(1000, KRW), Money.of(1500, KRW))), List.of())))
            .isInstanceOfSatisfying(ProductException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> prices.insertAll(List.of(),
            List.of(new SkuCurrencyPrice(950022, SKU + 4, Money.of(0, KRW))))))
            .isInstanceOfSatisfying(ProductException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT));
        assertThat(prices.findProductPrices(List.of(KRW_PRODUCT))).extracting(ProductCurrencyPrice::id).containsExactly(950020L);
    }

    /**
     * writer 가 부모 CAS 로 행을 잡은 채 가격을 쓰는 동안 backfill 이 같은 상품에 오면 fence 가 잠금을 기다리고,
     * writer 커밋 뒤 버전이 바뀌어 CONFLICT 로 건너뛴다. writer 의 값과 가격행은 덮이지 않는다.
     */
    @Test
    void backfillWaitsForWriterLockAndSkipsAsConflictWithoutOverwriting() throws Exception {
        var writerPrepared = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            Object plan = call.callRealMethod();
            if (first.getAndSet(false)) { // writer 의 prepare 만 멈추고 backfill 의 prepare 는 그대로 둔다
                writerPrepared.countDown();
                await(releaseWriter);
            }
            return plan;
        }).when(coordinator).prepare(any(), any());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> products.updatePricing(new ProductPricingUpdateCommand(KRW_PRODUCT, 0, 99, Money.of(1200, KRW), null)));
            await(writerPrepared);
            var report = executor.submit(() -> backfill.run(Mode.APPLY));
            Thread.sleep(300);
            assertThat(report.isDone()).isFalse(); // fence 가 writer 의 행 잠금을 기다린다
            releaseWriter.countDown();
            writer.get(15, TimeUnit.SECONDS);
            var result = report.get(15, TimeUnit.SECONDS);
            assertThat(result.tally().count(Outcome.CONFLICT)).isEqualTo(1);
            assertThat(result.tally().count(Outcome.FILLED)).isEqualTo(2);
        } finally {
            releaseWriter.countDown();
        }

        assertThat(jdbc.queryForObject("select version from product.product where id = ?", Long.class, KRW_PRODUCT)).isEqualTo(1L);
        assertThat(prices.findProductPrices(List.of(KRW_PRODUCT))).singleElement().satisfies(price -> {
            assertThat(price.salePrice()).isEqualTo(Money.of(1200, KRW));
            assertThat(price.listPrice()).isNull();
        });
        assertThat(prices.findSkuPrices(List.of(SKU, SKU + 1))).hasSize(2);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("transaction barrier timed out");
    }
}
