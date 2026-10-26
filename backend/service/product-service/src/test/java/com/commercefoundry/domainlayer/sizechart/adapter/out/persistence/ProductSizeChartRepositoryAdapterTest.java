package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.ProductSizeCellEntity;
import com.commercefoundry.domainlayer.sizechart.application.port.out.ProductSizeChartRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeCell;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeChart;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeRow;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.math.BigDecimal;
import java.util.List;
import org.hibernate.Session;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 상품 사이즈표 칸은 행 아이디 in 절 한 번으로 읽고, 빈 목록은 쿼리하지 않는다. 행을 지운 뒤 같은 순서로 다시 넣어도 유니크에 걸리지 않는다.
 * Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=product",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({ProductServiceFeaturesConfig.class, ProductSizeChartRepositoryAdapter.class})
class ProductSizeChartRepositoryAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ProductSizeChartRepositoryPort port;

    @Test
    @DisplayName("여러 행의 칸은 SELECT 한 번으로 읽고, 빈 아이디 목록은 쿼리하지 않는다")
    void cellsAreLoadedInOneQuery() {
        entityManager.persist(cell(1L, 11L, 101L, "10.00"));
        entityManager.persist(cell(2L, 11L, 102L, "20.00"));
        entityManager.persist(cell(3L, 12L, 101L, "11.00"));
        entityManager.persist(cell(4L, 12L, 102L, "21.50"));
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = statistics();
        statistics.clear();
        List<ProductSizeCell> found = port.findCellsByRowIds(List.of(11L, 12L));

        assertThat(found).extracting(ProductSizeCell::id).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        assertThat(found).extracting(ProductSizeCell::rowId).containsOnly(11L, 12L);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);

        statistics.clear();
        assertThat(port.findCellsByRowIds(List.of())).isEmpty();
        assertThat(statistics.getQueryExecutionCount()).isZero();
    }

    @Test
    @DisplayName("같은 순서 번호로 행을 다시 넣어도 유니크 키에 걸리지 않는다 — 지운 뒤에 넣기 때문이다")
    void replaceReusesDisplayOrder() {
        port.replace(chart(1L, null), List.of(row(11L, 1L, "44", 0)), List.of(cellValue(111L, 11L, 101L, "10.00")));
        entityManager.flush();
        entityManager.clear();

        port.replace(chart(1L, "비고"), List.of(row(12L, 1L, "55", 0)), List.of(cellValue(112L, 12L, 101L, "11.50")));
        entityManager.flush();
        entityManager.clear();

        ProductSizeChart reloaded = port.findByProductId(700L).orElseThrow();
        assertThat(reloaded.note()).isEqualTo("비고");
        assertThat(port.findRowsByChartId(reloaded.id())).extracting(ProductSizeRow::id).containsExactly(12L);
        assertThat(port.findCellsByRowIds(List.of(12L))).extracting(ProductSizeCell::cellValue).containsExactly(new BigDecimal("11.50"));
        assertThat(port.findCellsByRowIds(List.of(11L))).isEmpty();
    }

    @Test
    @DisplayName("치수 0 은 ck_product_size_cell_value_range 에 걸린다")
    void valueMustBePositive() {
        entityManager.persist(cell(1L, 11L, 101L, "0.00"));

        assertThatThrownBy(() -> entityManager.flush()).rootCause().hasMessageContaining("ck_product_size_cell_value_range");
    }

    private Statistics statistics() {
        Statistics statistics = entityManager.getEntityManager().unwrap(Session.class).getSessionFactory().getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    private static ProductSizeChart chart(long id, String note) {
        return new ProductSizeChart(id, 700L, 10L, note);
    }

    private static ProductSizeRow row(long id, long chartId, String label, int displayOrder) {
        return new ProductSizeRow(id, chartId, label, displayOrder);
    }

    private static ProductSizeCell cellValue(long id, long rowId, long columnId, String value) {
        return new ProductSizeCell(id, rowId, columnId, new BigDecimal(value));
    }

    private static ProductSizeCellEntity cell(long id, long rowId, long columnId, String value) {
        return ProductSizeCellEntity.builder().id(id).rowId(rowId).columnId(columnId).cellValue(new BigDecimal(value)).build();
    }
}
