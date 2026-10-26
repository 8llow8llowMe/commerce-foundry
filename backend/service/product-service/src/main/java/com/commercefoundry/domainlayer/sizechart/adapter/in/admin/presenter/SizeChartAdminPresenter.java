package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.item.ProductSizeCellAdminItem;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.item.ProductSizeRowAdminItem;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.item.SizeChartTemplateColumnAdminItem;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.item.SizeChartTemplateSummaryAdminItem;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.ProductSizeChartAdminResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartTemplateAdminResponse;
import com.commercefoundry.domainlayer.sizechart.application.info.ProductSizeChartInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartTemplateDetailInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartTemplateSummaryInfo;
import com.commercefoundry.persistence.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/** 어드민 사이즈표 응답. 식별자 {@code long → String}, 단위 → metadata 만 한다. 값은 저장 값 그대로다. */
@Component
public class SizeChartAdminPresenter {

    public PageResponse<SizeChartTemplateSummaryAdminItem> toPage(Page<SizeChartTemplateSummaryInfo> page) {
        return PageResponse.of(page).map(this::toSummary);
    }

    public SizeChartTemplateAdminResponse toTemplate(SizeChartTemplateDetailInfo info) {
        return SizeChartTemplateAdminResponse.builder()
            .templateId(Long.toString(info.templateId()))
            .name(info.name())
            .baseUnit(info.baseUnit().toMetadata())
            .columns(info.columns().stream().map(this::toColumn).toList())
            .build();
    }

    public ProductSizeChartAdminResponse toChart(ProductSizeChartInfo info) {
        return ProductSizeChartAdminResponse.builder()
            .productId(Long.toString(info.productId()))
            .templateId(Long.toString(info.templateId()))
            .templateName(info.templateName())
            .baseUnit(info.baseUnit().toMetadata())
            .note(info.note())
            .columns(info.columns().stream().map(this::toColumn).toList())
            .rows(info.rows().stream().map(this::toRow).toList())
            .build();
    }

    private SizeChartTemplateSummaryAdminItem toSummary(SizeChartTemplateSummaryInfo info) {
        return SizeChartTemplateSummaryAdminItem.builder()
            .templateId(Long.toString(info.templateId()))
            .name(info.name())
            .baseUnit(info.baseUnit().toMetadata())
            .columnCount(info.columnCount())
            .build();
    }

    private SizeChartTemplateColumnAdminItem toColumn(SizeChartTemplateDetailInfo.Column column) {
        return SizeChartTemplateColumnAdminItem.builder()
            .columnId(Long.toString(column.columnId()))
            .name(column.name())
            .description(column.description())
            .displayOrder(column.displayOrder())
            .build();
    }

    private ProductSizeRowAdminItem toRow(ProductSizeChartInfo.Row row) {
        return ProductSizeRowAdminItem.builder()
            .sizeLabel(row.sizeLabel())
            .displayOrder(row.displayOrder())
            .cells(row.cells().stream().map(this::toCell).toList())
            .build();
    }

    private ProductSizeCellAdminItem toCell(ProductSizeChartInfo.Cell cell) {
        return ProductSizeCellAdminItem.builder().columnId(Long.toString(cell.columnId())).value(cell.value()).build();
    }
}
