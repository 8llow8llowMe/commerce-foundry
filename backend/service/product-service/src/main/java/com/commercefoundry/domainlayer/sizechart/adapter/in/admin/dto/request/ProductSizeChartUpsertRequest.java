package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.validation.SizeChartText;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.validation.SizeChartValue;
import com.commercefoundry.domainlayer.sizechart.application.command.ProductSizeChartUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartValidationMessage;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartLimits;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

/** 상품 사이즈표 전체 교체. 각 행은 템플릿 항목을 빠짐없이 한 번씩 담는다. 값은 템플릿 저장 단위 그대로다. */
@Builder
@Schema(description = "상품 사이즈표 저장 요청 (전체 교체)")
public record ProductSizeChartUpsertRequest(

    @Schema(description = "사이즈표 템플릿 아이디. 문자열로 보내도 된다. 없으면 400(PRODUCT_065)이다", example = "4611686018427387904")
    @NotNull(message = SizeChartValidationMessage.TEMPLATE_ID_POSITIVE)
    @Positive(message = SizeChartValidationMessage.TEMPLATE_ID_POSITIVE)
    Long templateId,

    @Schema(description = "사이즈표 비고. 없으면 null. 앞뒤 공백을 빼고 500자 이하다", example = "측정은 옷을 펼친 기준입니다", nullable = true)
    @SizeChartText(
        maxCodePoints = SizeChartLimits.MAX_NOTE_CODE_POINTS,
        invalidCharacterMessage = SizeChartValidationMessage.NOTE_INVALID_CHARACTER,
        tooLongMessage = SizeChartValidationMessage.NOTE_SIZE_MAX
    )
    String note,

    @Schema(description = "사이즈 행. 1~30개. 사이즈 이름과 순서는 표 안에서 중복될 수 없다")
    @NotNull(message = SizeChartValidationMessage.ROWS_SIZE)
    @Size(min = SizeChartLimits.MIN_ROWS, max = SizeChartLimits.MAX_ROWS, message = SizeChartValidationMessage.ROWS_SIZE)
    List<@NotNull(message = SizeChartValidationMessage.ROW_REQUIRED) @Valid Row> rows
) {

    public ProductSizeChartUpsertCommand toCommand() {
        return ProductSizeChartUpsertCommand.builder()
            .templateId(templateId)
            .note(SizeChartTextValues.normalize(note))
            .rows(rows.stream().map(Row::toCommand).toList())
            .build();
    }

    @Builder
    @Schema(description = "사이즈 행")
    public record Row(

        @Schema(description = "사이즈 이름. 앞뒤 공백을 빼고 저장하며 뺀 뒤 1~20자다", example = "44")
        @SizeChartText(
            required = true,
            maxCodePoints = SizeChartLimits.MAX_SIZE_LABEL_CODE_POINTS,
            requiredMessage = SizeChartValidationMessage.SIZE_LABEL_REQUIRED,
            invalidCharacterMessage = SizeChartValidationMessage.SIZE_LABEL_INVALID_CHARACTER,
            tooLongMessage = SizeChartValidationMessage.SIZE_LABEL_SIZE_MAX
        )
        String sizeLabel,

        @Schema(description = "사이즈 노출 순서 (0 이상)", example = "0")
        @NotNull(message = SizeChartValidationMessage.ROW_DISPLAY_ORDER_REQUIRED)
        @PositiveOrZero(message = SizeChartValidationMessage.ROW_DISPLAY_ORDER_MIN)
        Integer displayOrder,

        @Schema(description = "템플릿 항목별 치수. 템플릿 항목을 빠짐없이 한 번씩 담는다")
        @NotNull(message = SizeChartValidationMessage.CELLS_REQUIRED)
        @Size(min = 1, message = SizeChartValidationMessage.CELLS_REQUIRED)
        List<@NotNull(message = SizeChartValidationMessage.CELL_REQUIRED) @Valid Cell> cells
    ) {

        private ProductSizeChartUpsertCommand.Row toCommand() {
            return ProductSizeChartUpsertCommand.Row.builder()
                .sizeLabel(SizeChartTextValues.normalize(sizeLabel))
                .displayOrder(displayOrder)
                .cells(cells.stream().map(Cell::toCommand).toList())
                .build();
        }
    }

    @Builder
    @Schema(description = "사이즈 칸")
    public record Cell(

        @Schema(description = "템플릿 항목 아이디. 문자열로 보내도 된다", example = "4611686018427387905")
        @NotNull(message = SizeChartValidationMessage.COLUMN_ID_POSITIVE)
        @Positive(message = SizeChartValidationMessage.COLUMN_ID_POSITIVE)
        Long columnId,

        @Schema(description = "치수. 0 초과 9999.99 이하, 소수 둘째 자리까지. JSON number 다", example = "86.5")
        @SizeChartValue
        BigDecimal value
    ) {

        private ProductSizeChartUpsertCommand.Cell toCommand() {
            return ProductSizeChartUpsertCommand.Cell.builder().columnId(columnId).value(value).build();
        }
    }
}
