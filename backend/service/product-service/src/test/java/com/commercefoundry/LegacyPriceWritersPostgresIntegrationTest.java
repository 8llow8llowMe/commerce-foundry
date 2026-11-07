package com.commercefoundry;

import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.ON_SALE;
import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.STOP_SELLING;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.commercefoundry.domainlayer.product.application.command.OptionRetirementCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductPricingUpdateCommand;
import com.commercefoundry.domainlayer.product.application.command.SkuAddCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class LegacyPriceWritersPostgresIntegrationTest extends LegacyPriceWritersIntegrationTest {
    @MockitoSpyBean ProductRepositoryPort productWrites;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @AfterEach
    void resetPricingSpy() {
        reset(productWrites);
    }

    @ParameterizedTest
    @EnumSource(value = Writer.class, names = {"ADD", "RETIRE"})
    void winningChildWriteHoldsParentLockAndRejectsStalePricingWithoutOverwritingPrices(Writer winner) throws Exception {
        var priceFlushed = new CountDownLatch(1);
        var releaseWinner = new CountDownLatch(1);
        var pricingReachedUpdate = new CountDownLatch(1);
        tx.executeWithoutResult(s -> {
            doAnswer(call -> {
                call.callRealMethod(); // Price rows have flushed; the child transaction still owns the parent row lock.
                priceFlushed.countDown();
                await(releaseWinner);
                return null;
            }).when(prices).insertAll(any(), any());
            doAnswer(call -> {
                pricingReachedUpdate.countDown();
                return call.callRealMethod();
            }).when(productWrites).update(any());
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var child = executor.submit(() -> {
                if (winner == Writer.ADD) {
                    skus.add(new SkuAddCommand(ID, 0, 99, List.of(), List.of(new SkuAddCommand.NewSku(
                        List.of("size2"), Money.of(50, KRW), ON_SALE, 100, null, new SkuAddCommand.InitialStock(true, 3)))));
                } else {
                    skus.retireOptions(new OptionRetirementCommand(ID, 0, 99, Set.of(), Set.of(VALUE)));
                }
            });
            try {
                await(priceFlushed);
                var pricing = executor.submit(() -> {
                    try {
                        products.updatePricing(new ProductPricingUpdateCommand(ID, 0, 99, Money.of(1500, KRW), null));
                        return "unexpected success";
                    } catch (ProductException exception) {
                        return exception.getErrorCode().getCode();
                    }
                });
                await(pricingReachedUpdate);
                assertThatThrownBy(() -> pricing.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseWinner.countDown();
                child.get(15, TimeUnit.SECONDS);
                assertThat(pricing.get(15, TimeUnit.SECONDS)).isEqualTo("PRODUCT_010");
            } finally {
                releaseWinner.countDown();
            }
        }

        assertThat(jdbc.queryForObject("select version from product.product where id = ?", Long.class, ID)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select sale_price_amount from product.product where id = ?", Long.class, ID)).isEqualTo(1000L);
        assertThat(prices.findProductPrices(List.of(ID))).singleElement().satisfies(price -> assertThat(price.salePrice()).isEqualTo(Money.of(1000, KRW)));
        var skuIds = jdbc.queryForList("select id from product.sku where product_id = ? order by id", Long.class, ID);
        assertThat(skuIds).hasSize(winner == Writer.ADD ? 3 : 2);
        var basePrices = prices.findSkuPrices(skuIds);
        assertThat(basePrices).hasSize(skuIds.size());
        for (var skuId : skuIds) {
            assertThat(basePrices.stream().filter(price -> price.skuId() == skuId)).singleElement().satisfies(price ->
                assertThat(price.additionalPrice().amount()).isEqualTo(jdbc.queryForObject(
                    "select additional_price_amount from product.sku where id = ?", Long.class, skuId)));
        }
        assertThat(jdbc.queryForObject("select sale_status from product.sku where id = ?", String.class, SKU))
            .isEqualTo(winner == Writer.RETIRE ? STOP_SELLING.name() : ON_SALE.name());
        assertThat(jdbc.queryForObject("select count(*) from product.stock_adjustment_history", Long.class))
            .isEqualTo(winner == Writer.ADD ? 1L : 0L);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("transaction barrier timed out");
    }
}
