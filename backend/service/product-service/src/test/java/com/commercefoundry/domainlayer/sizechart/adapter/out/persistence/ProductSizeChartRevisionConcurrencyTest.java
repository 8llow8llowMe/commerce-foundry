package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.sizechart.application.command.ProductSizeChartUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.port.out.ProductSizeChartRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartTemplateRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.ProductSizeChartProcessor;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeCell;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeChart;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeRow;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplate;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplateColumn;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL READ_COMMITTED must refresh the stale managed chart after the competing writer commits. */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=product"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, ProductSizeChartRepositoryAdapter.class,
    SizeChartTemplateRepositoryAdapter.class, ProductSizeChartProcessor.class})
class ProductSizeChartRevisionConcurrencyTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @Autowired private ProductSizeChartProcessor processor;
    @Autowired private ProductSizeChartRepositoryPort charts;
    @Autowired private SizeChartTemplateRepositoryPort templates;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoBean private SnowflakeIdGenerator ids;

    @Test
    void differentTemplateWritersRefreshPreloadedChartAndIncrementRevisionTwice() throws Exception {
        AtomicLong sequence = new AtomicLong(1000);
        when(ids.generateId()).thenAnswer(ignored -> sequence.incrementAndGet());
        var tx = new TransactionTemplate(transactions);
        tx.setTimeout(20);
        tx.executeWithoutResult(ignored -> {
            templates.insert(new SizeChartTemplate(10, "First", SizeChartUnit.CM), List.of(new SizeChartTemplateColumn(101, 10, "Chest", null, 0)));
            templates.insert(new SizeChartTemplate(20, "Second", SizeChartUnit.CM), List.of(new SizeChartTemplateColumn(201, 20, "Chest", null, 0)));
            charts.replace(new ProductSizeChart(1, 700, 10, "original"),
                List.of(new ProductSizeRow(11, 1, "S", 0)), List.of(new ProductSizeCell(111, 11, 101, new BigDecimal("50.00"))));
        });
        var secondPreloaded = new CountDownLatch(1);
        var firstWritten = new CountDownLatch(1);
        var secondAttempting = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        Map<Long, Product> found = Map.of(700L, mock(Product.class));
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var first = executor.submit(() -> tx.executeWithoutResult(ignored -> {
                    templates.lockById(10).orElseThrow();
                    await(secondPreloaded);
                    processor.replace(700, found, command(10, 101, "first", "51"));
                    firstWritten.countDown();
                    await(releaseFirst); // Keep the chart write lock while the other template writer waits.
                }));
                var second = executor.submit(() -> tx.executeWithoutResult(ignored -> {
                    templates.lockById(20).orElseThrow();
                    var stale = charts.findByProductId(700).orElseThrow();
                    assertThat(stale.revision()).isZero();
                    assertThat(stale.templateId()).isEqualTo(10);
                    secondPreloaded.countDown();
                    await(firstWritten);
                    secondAttempting.countDown();
                    processor.replace(700, found, command(20, 201, "second", "62"));
                }));
                await(secondAttempting);
                // Independent template locks permit both writers to reach the shared chart row lock.
                assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseFirst.countDown();
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
            }
        }
        tx.executeWithoutResult(ignored -> {
            var chart = charts.findByProductId(700).orElseThrow();
            assertThat(chart.id()).isEqualTo(1);
            assertThat(chart.revision()).isEqualTo(2);
            assertThat(chart.templateId()).isEqualTo(20);
            assertThat(chart.note()).isEqualTo("second");
            var rows = charts.findRowsByChartId(chart.id());
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().sizeChartId()).isEqualTo(1);
            var cells = charts.findCellsByRowIds(List.of(rows.getFirst().id()));
            assertThat(cells).hasSize(1);
            assertThat(cells.getFirst().columnId()).isEqualTo(201);
            assertThat(cells.getFirst().cellValue()).isEqualByComparingTo("62");
        });
    }

    private static ProductSizeChartUpsertCommand command(long templateId, long columnId, String note, String value) {
        return new ProductSizeChartUpsertCommand(templateId, note, List.of(new ProductSizeChartUpsertCommand.Row("S", 0,
            List.of(new ProductSizeChartUpsertCommand.Cell(columnId, new BigDecimal(value))))));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("writer synchronization").isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
