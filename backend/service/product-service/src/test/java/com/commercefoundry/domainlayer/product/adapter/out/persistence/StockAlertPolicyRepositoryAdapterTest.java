package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.product.adapter.in.admin.presenter.StockAlertAdminPresenter;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.SkuRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockAlertPolicyRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockRepository;
import com.commercefoundry.domainlayer.product.application.mapper.ProductMapperImpl;
import com.commercefoundry.domainlayer.product.application.mapper.SkuMapperImpl;
import com.commercefoundry.domainlayer.product.application.mapper.StockAlertPolicyMapperImpl;
import com.commercefoundry.domainlayer.product.application.port.out.StockAlertPolicyRepositoryPort;
import com.commercefoundry.domainlayer.product.application.service.StockAlertAdminFacade;
import com.commercefoundry.domainlayer.product.application.service.processor.StockAlertAuditProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.StockAlertProcessor;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.StockAlertPolicy;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL 17의 실제 JOIN/count, 생성·수정·삭제와 SKU 잠금 직렬화를 검증한다. */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=product",
    "spring.jpa.properties.hibernate.generate_statistics=true"})
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, StockAlertPolicyRepositoryAdapter.class, StockAlertPolicyMapperImpl.class,
    ProductRepositoryAdapter.class, ProductMapperImpl.class, SkuRepositoryAdapter.class, SkuMapperImpl.class, StockAlertProcessor.class,
    StockAlertAdminFacade.class, StockAlertAdminPresenter.class, StockAlertAuditProcessor.class, StockAlertPolicyRepositoryAdapterTest.ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StockAlertPolicyRepositoryAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @TestConfiguration
    static class ClockConfig {
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    @Autowired private ProductRepository products;
    @Autowired private SkuRepository skus;
    @Autowired private StockRepository stocks;
    @Autowired private StockAlertPolicyRepository policies;
    @Autowired private StockAlertPolicyRepositoryPort port;
    @Autowired private StockAlertAdminFacade facade;
    @Autowired private TransactionTemplate transactions;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void setUp() {
        products.saveAllAndFlush(List.of(product(1, true, false), product(2, false, false), product(3, false, true)));
        for (long id = 1; id <= 7; id++) {
            skus.saveAndFlush(SkuEntity.builder().id(id).productId(id == 5 ? 2L : id == 6 ? 3L : 1L).optionValueKey(String.valueOf(id))
                .saleStatus(id == 5 ? SaleStatus.STOP_SELLING : SaleStatus.ON_SALE).additionalPriceCurrency(CurrencyCode.KRW).build());
            if (id != 1) {
                stocks.saveAndFlush(StockEntity.builder().skuId(id).stockManaged(id != 4).availableQuantity(id == 2 ? 2 : id == 3 ? 3 : id == 5 ? 1 : 0).build());
            }
            if (id != 7) {
                long skuId = id;
                transactions.executeWithoutResult(status -> port.save(StockAlertPolicy.configure(skuId, skuId == 1 || skuId == 6 ? 0 : 2)));
            }
        }
    }

    @AfterEach
    void tearDown() {
        policies.deleteAllInBatch();
        stocks.deleteAllInBatch();
        skus.deleteAllInBatch();
        products.deleteAllInBatch();
    }

    @Test
    void queryUsesDefaultsAndSameEligibilityForPageAndCount() {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var first = facade.getLowStocks(0, 2);
        assertThat(statistics.getPrepareStatementCount()).as("목록과 count만 조회하며 SKU별 단건 조회를 하지 않는다").isEqualTo(2);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.contents()).extracting(item -> item.skuId()).containsExactly("1", "5");
        assertThat(first.contents().getFirst().availableQuantity()).isZero();
        assertThat(first.contents().get(1).productVisible()).isFalse();
        assertThat(first.contents().get(1).skuSaleStatus().code()).isEqualTo("STOP_SELLING");
        assertThat(facade.getLowStocks(1, 2).contents()).extracting(item -> item.skuId()).containsExactly("2");
        assertThat(facade.getLowStocks(2, 2).totalElements()).isEqualTo(3);
        assertThat(facade.getLowStocks(2, 2).contents()).isEmpty();
    }

    @Test
    void firstConcurrentRegistrationsKeepOnePolicyAndPreserveStock() throws Exception {
        facade.getPolicy(7);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return facade.setPolicy(7, 1, 7); });
            var two = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return facade.setPolicy(7, 2, 8); });
            start.countDown();
            assertThat(one.get(20, TimeUnit.SECONDS).enabled()).isTrue();
            assertThat(two.get(20, TimeUnit.SECONDS).enabled()).isTrue();
        }
        assertThat(policies.findAll().stream().filter(policy -> policy.getSkuId() == 7)).hasSize(1);
        assertThat(facade.getPolicy(7).thresholdQuantity()).isIn(1L, 2L);
        assertThat(stocks.findById(7L).orElseThrow().getAvailableQuantity()).isZero();
    }

    @Test
    void clearWaitsForInFlightSetAndDoesNotResurrectPolicy() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var deleting = new CountDownLatch(1);
        var deleted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var set = pool.submit(() -> transactions.executeWithoutResult(status -> {
                facade.setPolicy(2, 9, 7);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var clear = pool.submit(() -> { deleting.countDown(); facade.clearPolicy(2, 8); deleted.countDown(); });
            assertThat(deleting.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(deleted.await(100, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            set.get(20, TimeUnit.SECONDS);
            clear.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertThat(facade.getPolicy(2).enabled()).isFalse();
        assertThat(stocks.findById(2L).orElseThrow().getAvailableQuantity()).isEqualTo(2);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("release timeout"); }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static ProductEntity product(long id, boolean visible, boolean deleted) {
        return ProductEntity.builder().id(id).categoryId(1L).name("상품" + id).visible(visible)
            .saleStatus(deleted || !visible ? SaleStatus.STOP_SELLING : SaleStatus.ON_SALE)
            .salePriceAmount(1000).salePriceCurrency(CurrencyCode.KRW).deletedAt(deleted ? Instant.now() : null).build();
    }
}
