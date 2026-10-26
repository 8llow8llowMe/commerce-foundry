package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence;

import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.ProductSizeCellEntity;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.ProductSizeChartEntity;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.ProductSizeRowEntity;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository.ProductSizeCellRepository;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository.ProductSizeChartRepository;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository.ProductSizeRowRepository;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartException;
import com.commercefoundry.domainlayer.sizechart.application.port.out.ProductSizeChartRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeCell;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeChart;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeRow;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductSizeChartRepositoryAdapter implements ProductSizeChartRepositoryPort {

    private static final String UK_PRODUCT_ID = "uk_product_size_chart_product_id";

    private final ProductSizeChartRepository productSizeChartRepository;
    private final ProductSizeRowRepository productSizeRowRepository;
    private final ProductSizeCellRepository productSizeCellRepository;

    @Override
    public Optional<ProductSizeChart> findByProductId(long productId) {
        return productSizeChartRepository.findByProductId(productId).map(this::toDomain);
    }

    @Override
    public boolean existsByTemplateId(long templateId) {
        return productSizeChartRepository.existsByTemplateId(templateId);
    }

    @Override
    public List<ProductSizeRow> findRowsByChartId(long sizeChartId) {
        return productSizeRowRepository.findBySizeChartIdOrderByDisplayOrderAscIdAsc(sizeChartId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<ProductSizeCell> findCellsByRowIds(Collection<Long> rowIds) {
        if (rowIds.isEmpty()) {
            return List.of();
        }
        return productSizeCellRepository.findByRowIdIn(rowIds).stream().map(this::toDomain).toList();
    }

    /**
     * 잠근 행이 있으면 그 아이디를 쓰고, 없으면 호출자가 발급한 아이디로 넣는다.
     * 다른 템플릿 잠금을 잡은 저장이 사이에 커밋했을 수 있어, 호출자가 넘긴 표 아이디와 달라도 잠근 행을 기준으로 행을 다시 단다.
     */
    @Override
    public void replace(ProductSizeChart chart, List<ProductSizeRow> rows, List<ProductSizeCell> cells) {
        Optional<ProductSizeChartEntity> locked = productSizeChartRepository.findByProductIdForUpdate(chart.productId());
        long chartId;
        if (locked.isPresent()) {
            ProductSizeChartEntity entity = locked.get();
            chartId = entity.getId();
            productSizeCellRepository.deleteBySizeChartId(chartId);
            productSizeRowRepository.deleteBySizeChartId(chartId);
            entity.change(chart.templateId(), chart.note());
        } else {
            chartId = chart.id();
            productSizeChartRepository.save(toEntity(chart));
        }
        productSizeRowRepository.saveAll(rows.stream().map(row -> toEntity(row, chartId)).toList());
        productSizeCellRepository.saveAll(cells.stream().map(this::toEntity).toList());
        try {
            productSizeChartRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.violates(exception, UK_PRODUCT_ID)) {
                throw new SizeChartException(SizeChartErrorCode.CHART_CONFLICT, exception);
            }
            throw exception;
        }
    }

    private ProductSizeChart toDomain(ProductSizeChartEntity entity) {
        return new ProductSizeChart(entity.getId(), entity.getProductId(), entity.getTemplateId(), entity.getNote());
    }

    private ProductSizeRow toDomain(ProductSizeRowEntity entity) {
        return new ProductSizeRow(entity.getId(), entity.getSizeChartId(), entity.getSizeLabel(), entity.getDisplayOrder());
    }

    private ProductSizeCell toDomain(ProductSizeCellEntity entity) {
        return new ProductSizeCell(entity.getId(), entity.getRowId(), entity.getColumnId(), entity.getCellValue());
    }

    private ProductSizeChartEntity toEntity(ProductSizeChart chart) {
        return ProductSizeChartEntity.builder()
            .id(chart.id())
            .productId(chart.productId())
            .templateId(chart.templateId())
            .note(chart.note())
            .build();
    }

    private ProductSizeRowEntity toEntity(ProductSizeRow row, long sizeChartId) {
        return ProductSizeRowEntity.builder()
            .id(row.id())
            .sizeChartId(sizeChartId)
            .sizeLabel(row.sizeLabel())
            .displayOrder(row.displayOrder())
            .build();
    }

    private ProductSizeCellEntity toEntity(ProductSizeCell cell) {
        return ProductSizeCellEntity.builder()
            .id(cell.id())
            .rowId(cell.rowId())
            .columnId(cell.columnId())
            .cellValue(cell.cellValue())
            .build();
    }
}
