package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.sizechart.application.port.out.ProductSizeChartRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartTemplateRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeCell;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeChart;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeRow;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplate;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplateColumn;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** H2에서 부모 행과 자식 교체를 실제 저장한다. PostgreSQL 잠금 경합 검증은 별도로 수행한다. */
@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:size-chart-revision;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class,
    ProductSizeChartRepositoryAdapter.class, SizeChartTemplateRepositoryAdapter.class})
class SizeChartRevisionPersistenceTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ProductSizeChartRepositoryPort charts;

    @Autowired
    private SizeChartTemplateRepositoryPort templates;

    @Test
    @DisplayName("상품 표는 0으로 시작하고 셀만 수정하거나 같은 내용으로 교체해도 리비전이 증가한다")
    void chartReplacementAdvancesRevisionAndKeepsBaselineStale() {
        templates.insert(new SizeChartTemplate(10L, "상의", SizeChartUnit.CM), List.of(column(101L, "가슴", null, 0)));
        replaceChart(1L, 11L, 111L, "50.00");
        flushAndClear();

        ProductSizeChart baseline = charts.findByProductId(700L).orElseThrow();
        assertThat(baseline.revision()).isZero();

        // 이전 조회 아이디와 달라도 잠근 표의 아이디를 쓰고, 셀만 바뀌어도 부모 리비전을 갱신한다.
        replaceChart(999L, 12L, 112L, "51.00");
        flushAndClear();

        ProductSizeChart changed = charts.findByProductId(700L).orElseThrow();
        assertThat(changed.id()).isEqualTo(baseline.id());
        assertThat(changed.templateId()).isEqualTo(baseline.templateId());
        assertThat(changed.note()).isEqualTo(baseline.note());
        assertThat(changed.revision()).isEqualTo(1L).isGreaterThan(baseline.revision());
        assertThat(baseline.revision()).isZero();
        assertThat(charts.findRowsByChartId(changed.id())).containsExactly(new ProductSizeRow(12L, 1L, "S", 0));
        assertThat(charts.findCellsByRowIds(List.of(11L))).isEmpty();
        assertThat(charts.findCellsByRowIds(List.of(12L))).extracting(ProductSizeCell::cellValue).containsExactly(new BigDecimal("51.00"));

        replaceChart(1L, 13L, 113L, "51.00");
        flushAndClear();

        assertThat(charts.findByProductId(700L).orElseThrow().revision()).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("템플릿은 0으로 시작하고 두 수정 경로 모두 열만 수정하거나 같은 내용을 보내도 리비전이 증가한다")
    void templateColumnUpdatesAdvanceRevision(boolean referenced) {
        SizeChartTemplate input = new SizeChartTemplate(10L, "상의", SizeChartUnit.CM);
        templates.insert(input, List.of(column(101L, "가슴", null, 0), column(102L, "총장", null, 1)));
        if (referenced) {
            charts.replace(new ProductSizeChart(1L, 700L, 10L, null), List.of(), List.of());
        }
        flushAndClear();

        SizeChartTemplate baseline = templates.findById(10L).orElseThrow();
        assertThat(baseline.revision()).isZero();

        List<SizeChartTemplateColumn> columns = referenced
            ? List.of(column(101L, "총장", "설명 수정", 1), column(102L, "가슴", null, 0))
            : List.of(column(201L, "총장", "설명 수정", 1), column(202L, "가슴", null, 0));
        updateTemplate(input, columns, referenced);
        flushAndClear();

        SizeChartTemplate changed = templates.findById(10L).orElseThrow();
        assertThat(changed.name()).isEqualTo(baseline.name());
        assertThat(changed.baseUnit()).isEqualTo(baseline.baseUnit());
        assertThat(changed.revision()).isEqualTo(1L).isGreaterThan(baseline.revision());
        assertThat(baseline.revision()).isZero();
        assertThat(templates.findColumns(10L)).containsExactly(columns.get(1), columns.get(0));
        assertThat(templates.findPage(0, 10).getContent()).containsExactly(changed);
        assertThat(templates.lockById(10L).orElseThrow().revision()).isEqualTo(1L);

        List<SizeChartTemplateColumn> nextColumns = referenced ? columns
            : List.of(column(301L, "총장", "설명 수정", 1), column(302L, "가슴", null, 0));
        updateTemplate(input, nextColumns, referenced);
        flushAndClear();

        assertThat(templates.findById(10L).orElseThrow().revision()).isEqualTo(2L);
        if (referenced) {
            assertThat(charts.findByProductId(700L).orElseThrow().revision()).isZero();
        }
    }

    private void replaceChart(long chartId, long rowId, long cellId, String value) {
        templates.lockById(10L).orElseThrow();
        charts.replace(new ProductSizeChart(chartId, 700L, 10L, null),
            List.of(new ProductSizeRow(rowId, chartId, "S", 0)),
            List.of(new ProductSizeCell(cellId, rowId, 101L, new BigDecimal(value))));
    }

    private void updateTemplate(SizeChartTemplate input, List<SizeChartTemplateColumn> columns, boolean referenced) {
        templates.lockById(input.id()).orElseThrow();
        if (referenced) {
            templates.updateColumns(input, columns);
        } else {
            templates.replaceColumns(input, columns);
        }
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static SizeChartTemplateColumn column(long id, String name, String description, int order) {
        return new SizeChartTemplateColumn(id, 10L, name, description, order);
    }
}
