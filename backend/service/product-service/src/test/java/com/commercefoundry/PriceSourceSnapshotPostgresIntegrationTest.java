package com.commercefoundry;

import static com.commercefoundry.domainlayer.product.application.exception.PriceSourceSnapshotReadException.Reason.PRODUCT_MISSING;
import static com.commercefoundry.domainlayer.product.application.exception.PriceSourceSnapshotReadException.Reason.READ_FAILED;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceSourceSnapshotQueryResult;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class PriceSourceSnapshotPostgresIntegrationTest extends PriceSourceSnapshotIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager em;

    @Test
    void writerCommitsBetweenQueriesButAllFourSourcesUseTheFirstSnapshot() {
        seedFourSources();
        measure();
        SqlProbe.BEFORE_SECOND.set(() -> {
            assertThat(jdbc.queryForObject("show transaction_isolation", String.class)).isEqualTo("repeatable read");
            assertThat(jdbc.queryForObject("show transaction_read_only", String.class)).isEqualTo("on");
            CompletableFuture.runAsync(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.update("update product.product set sale_price_amount=7, version=version+1 where id=1");
                jdbc.update("update product.sku set additional_price_amount=7 where id=10");
                jdbc.update("update product.product_price set sale_price_amount=7 where id=100");
                jdbc.update("update product.sku_price set additional_price_amount=7 where id=200");
            })).orTimeout(3, TimeUnit.SECONDS).join();
        });
        try {
            assertVersionAndAmounts(port.readByProductIds(List.of(1L)), 0, 0);
            assertSelects(4);
        } finally {
            SqlProbe.BEFORE_SECOND.remove();
        }
        measure();
        assertVersionAndAmounts(port.readByProductIds(List.of(1L)), 1, 7);
        assertSelects(4);
    }

    @Test
    void dirtyCallerIsSuspendedAndResumedAfterSuccessAndFailureWithoutFlush() {
        seedFourSources();
        var before = storedRows();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var caller = em.find(ProductEntity.class, 1L);
            ReflectionTestUtils.setField(caller, "name", "dirty-caller");
            measure();
            assertThat(port.readByProductIds(List.of(1L)).products().getFirst().name()).isEqualTo(MARKER);
            assertSelects(4);
            measure();
            PriceSourceSnapshotRepositoryAdapterTest.assertFailure(catchThrowable(() -> port.readByProductIds(List.of(99L))), PRODUCT_MISSING);
            assertSelects(1);
            assertThat(em.contains(caller)).isTrue();
            assertThat(caller.getName()).isEqualTo("dirty-caller");
            assertThat(storedRows()).isEqualTo(before);
            status.setRollbackOnly();
        });
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void accessExclusiveLockTriggersServerQueryTimeoutAndReleasesTheReadConnection() throws Exception {
        seedFourSources();
        var before = storedRows();
        var pool = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
        var logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try (Connection blocker = dataSource.getConnection(); var statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            try {
                statement.execute("lock table product.product in access exclusive mode");
                int borrowed = pool.getHikariPoolMXBean().getActiveConnections();
                measure();
                long start = System.nanoTime();
                PriceSourceSnapshotRepositoryAdapterTest.assertFailure(catchThrowable(() -> port.readByProductIds(List.of(1L))), READ_FAILED);
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isBetween(1000L, 4500L);
                assertSelects(1);
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(borrowed);
                assertThat(events.list).anyMatch(event -> event.getFormattedMessage().contains("57014"));
                assertThat(events.list).allSatisfy(event -> {
                    assertThat(event.getFormattedMessage()).doesNotContain(MARKER);
                    if (event.getThrowableProxy() != null) {
                        assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain(MARKER);
                    }
                });
            } finally {
                blocker.rollback();
            }
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
        assertVersionAndAmounts(port.readByProductIds(List.of(1L)), 0, 0);
        assertThat(storedRows()).isEqualTo(before);
    }

    private void seedFourSources() {
        product(1, "KRW", false);
        sku(10, 1, "KRW", false);
        productPrice(100, 1, "KRW", 0);
        skuPrice(200, 10, "KRW", 0);
    }

    private void assertVersionAndAmounts(PriceSourceSnapshotQueryResult view, long version, long amount) {
        assertThat(view.products().getFirst().version()).isEqualTo(version);
        assertThat(view.products().getFirst().salePrice()).isEqualTo(Money.of(amount, KRW));
        assertThat(view.skus().getFirst().additionalPrice()).isEqualTo(Money.of(amount, KRW));
        assertThat(view.productPrices().getFirst().salePrice()).isEqualTo(Money.of(amount, KRW));
        assertThat(view.skuPrices().getFirst().additionalPrice()).isEqualTo(Money.of(amount, KRW));
    }
}
