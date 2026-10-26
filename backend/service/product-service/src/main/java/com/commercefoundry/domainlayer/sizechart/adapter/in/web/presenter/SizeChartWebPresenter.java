package com.commercefoundry.domainlayer.sizechart.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.item.ProductSizeChartCellItem;
import com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.item.ProductSizeChartColumnItem;
import com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.item.ProductSizeChartRowItem;
import com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.response.ProductSizeChartResponse;
import com.commercefoundry.domainlayer.sizechart.application.info.ProductSizeChartInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartTemplateDetailInfo;
import org.springframework.stereotype.Component;

/** 고객 사이즈표 응답. 식별자 {@code long → String}, 단위 → metadata 만 한다. 값 변환은 Processor 가 끝냈다. */
@Component
public class SizeChartWebPresenter {

    public ProductSizeChartResponse toResponse(ProductSizeChartInfo info) {
        return ProductSizeChartResponse.builder()
            .productId(Long.toString(info.productId()))
            .templateId(Long.toString(info.templateId()))
            .templateName(info.templateName())
            .baseUnit(info.baseUnit().toMetadata())
            .displayUnit(info.displayUnit().toMetadata())
            .note(info.note())
            .columns(info.columns().stream().map(this::toColumn).toList())
            .rows(info.rows().stream().map(this::toRow).toList())
            .build();
    }

    private ProductSizeChartColumnItem toColumn(SizeChartTemplateDetailInfo.Column column) {
        return ProductSizeChartColumnItem.builder()
            .columnId(Long.toString(column.columnId()))
            .name(column.name())
            .description(column.description())
            .displayOrder(column.displayOrder())
            .build();
    }

    private ProductSizeChartRowItem toRow(ProductSizeChartInfo.Row row) {
        return ProductSizeChartRowItem.builder()
            .sizeLabel(row.sizeLabel())
            .displayOrder(row.displayOrder())
            .cells(row.cells().stream().map(this::toCell).toList())
            .build();
    }

    private ProductSizeChartCellItem toCell(ProductSizeChartInfo.Cell cell) {
        return ProductSizeChartCellItem.builder().columnId(Long.toString(cell.columnId())).value(cell.value()).build();
    }
}
