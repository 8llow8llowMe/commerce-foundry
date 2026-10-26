package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.sizechart.application.command.ProductSizeChartUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartException;
import com.commercefoundry.domainlayer.sizechart.application.info.ProductSizeChartInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartTemplateDetailInfo;
import com.commercefoundry.domainlayer.sizechart.application.port.out.ProductSizeChartRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartTemplateRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeCell;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeChart;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeRow;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartCellViolation;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartCells;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartLimits;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplate;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplateColumn;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnits;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 사이즈표 읽기 · 전체 교체. 칸은 행 아이디를 모아 in 절 한 번으로 읽는다.
 *
 * <p>상품이 있는지는 Facade 가 상품 컨텍스트 Processor 로 확인해 맵으로 넘긴다 (전시 {@code DisplaySectionProcessor#validateProductsExist} 와 같은 자리).
 * 쓰기는 템플릿 행을 먼저 잠근 뒤 표를 잠가, 템플릿을 지우는 쓰기와 순서가 엇갈리지 않게 한다.
 */
@Component
@RequiredArgsConstructor
public class ProductSizeChartProcessor {

    private static final Comparator<SizeChartTemplateColumn> COLUMN_ORDER = Comparator
        .comparingInt(SizeChartTemplateColumn::displayOrder)
        .thenComparingLong(SizeChartTemplateColumn::id);

    private final SizeChartTemplateRepositoryPort sizeChartTemplateRepositoryPort;
    private final ProductSizeChartRepositoryPort productSizeChartRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 숨김 포함 조회 결과에 상품이 없으면 {@code PRODUCT_064}(404)다.
     *
     * @param found 상품 컨텍스트가 숨김까지 읽어 온 결과
     */
    public void requireProduct(long productId, Map<Long, Product> found) {
        if (!found.containsKey(productId)) {
            throw new SizeChartException(SizeChartErrorCode.PRODUCT_NOT_FOUND);
        }
    }

    /** 표가 없으면 null. {@code displayUnit} 이 null 이면 저장 단위 그대로다. */
    public ProductSizeChartInfo find(long productId, SizeChartUnit displayUnit) {
        return productSizeChartRepositoryPort.findByProductId(productId)
            .map(chart -> toInfo(chart, displayUnit))
            .orElse(null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProductSizeChartInfo replace(long productId, Map<Long, Product> found, ProductSizeChartUpsertCommand command) {
        requireProduct(productId, found);
        requireDistinctRows(command.rows());
        SizeChartTemplate template = sizeChartTemplateRepositoryPort.lockById(command.templateId())
            .orElseThrow(() -> new SizeChartException(SizeChartErrorCode.TEMPLATE_REFERENCE_NOT_FOUND));
        List<SizeChartTemplateColumn> columns = sizeChartTemplateRepositoryPort.findColumns(template.id());
        Set<Long> columnIds = new HashSet<>();
        columns.forEach(column -> columnIds.add(column.id()));
        for (ProductSizeChartUpsertCommand.Row row : command.rows()) {
            throwIfInvalid(SizeChartCells.violation(columnIds, row.cells().stream().map(ProductSizeChartUpsertCommand.Cell::columnId).toList()));
        }

        long chartId = productSizeChartRepositoryPort.findByProductId(productId).map(ProductSizeChart::id).orElseGet(snowflakeIdGenerator::generateId);
        ProductSizeChart chart = new ProductSizeChart(chartId, productId, template.id(), command.note());
        List<ProductSizeRow> rows = new ArrayList<>(command.rows().size());
        List<ProductSizeCell> cells = new ArrayList<>();
        for (ProductSizeChartUpsertCommand.Row row : command.rows()) {
            long rowId = snowflakeIdGenerator.generateId();
            rows.add(new ProductSizeRow(rowId, chartId, row.sizeLabel(), row.displayOrder()));
            for (ProductSizeChartUpsertCommand.Cell cell : row.cells()) {
                cells.add(new ProductSizeCell(snowflakeIdGenerator.generateId(), rowId, cell.columnId(), stored(cell.value())));
            }
        }
        productSizeChartRepositoryPort.replace(chart, rows, cells);
        return toInfo(chart, template, columns, rows, cells, template.baseUnit());
    }

    private ProductSizeChartInfo toInfo(ProductSizeChart chart, SizeChartUnit displayUnit) {
        SizeChartTemplate template = sizeChartTemplateRepositoryPort.findById(chart.templateId())
            .orElseThrow(() -> new SizeChartException(SizeChartErrorCode.NOT_FOUND_TEMPLATE));
        List<SizeChartTemplateColumn> columns = sizeChartTemplateRepositoryPort.findColumns(template.id());
        List<ProductSizeRow> rows = productSizeChartRepositoryPort.findRowsByChartId(chart.id());
        List<Long> rowIds = rows.stream().map(ProductSizeRow::id).toList();
        List<ProductSizeCell> cells = productSizeChartRepositoryPort.findCellsByRowIds(rowIds);
        return toInfo(chart, template, columns, rows, cells, displayUnit == null ? template.baseUnit() : displayUnit);
    }

    private static ProductSizeChartInfo toInfo(
        ProductSizeChart chart,
        SizeChartTemplate template,
        List<SizeChartTemplateColumn> columns,
        List<ProductSizeRow> rows,
        List<ProductSizeCell> cells,
        SizeChartUnit displayUnit
    ) {
        List<SizeChartTemplateColumn> orderedColumns = columns.stream().sorted(COLUMN_ORDER).toList();
        Map<Long, Integer> orderByColumn = new HashMap<>();
        List<SizeChartTemplateDetailInfo.Column> columnInfos = new ArrayList<>(orderedColumns.size());
        for (SizeChartTemplateColumn column : orderedColumns) {
            orderByColumn.put(column.id(), column.displayOrder());
            columnInfos.add(new SizeChartTemplateDetailInfo.Column(column.id(), column.name(), column.description(), column.displayOrder()));
        }
        Map<Long, List<ProductSizeCell>> cellsByRow = new HashMap<>();
        for (ProductSizeCell cell : cells) {
            cellsByRow.computeIfAbsent(cell.rowId(), ignored -> new ArrayList<>()).add(cell);
        }
        List<ProductSizeRow> orderedRows = rows.stream()
            .sorted(Comparator.comparingInt(ProductSizeRow::displayOrder).thenComparingLong(ProductSizeRow::id))
            .toList();
        List<ProductSizeChartInfo.Row> rowInfos = new ArrayList<>(orderedRows.size());
        for (ProductSizeRow row : orderedRows) {
            List<ProductSizeCell> rowCells = cellsByRow.getOrDefault(row.id(), List.of());
            List<ProductSizeChartInfo.Cell> cellInfos = rowCells.stream()
                .sorted(Comparator.comparingInt((ProductSizeCell cell) -> orderByColumn.getOrDefault(cell.columnId(), Integer.MAX_VALUE))
                    .thenComparingLong(ProductSizeCell::columnId))
                .map(cell -> new ProductSizeChartInfo.Cell(cell.columnId(), SizeChartUnits.toDisplay(cell.cellValue(), template.baseUnit(), displayUnit)))
                .toList();
            rowInfos.add(new ProductSizeChartInfo.Row(row.sizeLabel(), row.displayOrder(), cellInfos));
        }
        return new ProductSizeChartInfo(
            chart.productId(), template.id(), template.name(), template.baseUnit(), displayUnit, chart.note(), columnInfos, rowInfos
        );
    }

    private static void throwIfInvalid(SizeChartCellViolation violation) {
        switch (violation) {
            case DUPLICATED -> throw new SizeChartException(SizeChartErrorCode.CELL_COLUMN_DUPLICATED);
            case UNKNOWN -> throw new SizeChartException(SizeChartErrorCode.CELL_COLUMN_UNKNOWN);
            case MISSING -> throw new SizeChartException(SizeChartErrorCode.CELL_COLUMN_MISSING);
            case NONE -> {
                // 템플릿 항목과 칸이 같다
            }
        }
    }

    private static void requireDistinctRows(List<ProductSizeChartUpsertCommand.Row> rows) {
        Set<String> labels = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (ProductSizeChartUpsertCommand.Row row : rows) {
            if (!labels.add(row.sizeLabel())) {
                throw new SizeChartException(SizeChartErrorCode.ROW_LABEL_DUPLICATED);
            }
            if (!orders.add(row.displayOrder())) {
                throw new SizeChartException(SizeChartErrorCode.ROW_ORDER_DUPLICATED);
            }
        }
    }

    /** 요청 검증을 지난 값(소수 둘째 자리 이하)을 저장 scale 로 맞춘다. 반올림은 하지 않는다. */
    private static BigDecimal stored(BigDecimal value) {
        return value.setScale(SizeChartLimits.MEASUREMENT_SCALE, RoundingMode.UNNECESSARY);
    }
}
