package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.validation.SizeChartText;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartTemplateUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartValidationMessage;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartUnitCode;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartLimits;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Builder;

/** 사이즈표 템플릿 생성 · 전체 교체. 쓰는 중인 템플릿을 고칠 때만 {@code columnId} 가 필요하다. */
@Builder
@Schema(description = "사이즈표 템플릿 생성 · 수정 요청 (수정은 전체 교체)")
public record SizeChartTemplateUpsertRequest(

    @Schema(description = "템플릿 이름. 앞뒤 공백을 빼고 저장하며 뺀 뒤 1~50자다", example = "여성 상의")
    @SizeChartText(
        required = true,
        maxCodePoints = SizeChartLimits.MAX_TEMPLATE_NAME_CODE_POINTS,
        requiredMessage = SizeChartValidationMessage.TEMPLATE_NAME_REQUIRED,
        invalidCharacterMessage = SizeChartValidationMessage.TEMPLATE_NAME_INVALID_CHARACTER,
        tooLongMessage = SizeChartValidationMessage.TEMPLATE_NAME_SIZE_MAX
    )
    String name,

    @Schema(description = "치수를 저장하는 단위 — CM(센티미터) · INCH(인치). 화면 변환은 조회 응답에서만 한다", example = "CM")
    @NotNull(message = SizeChartValidationMessage.BASE_UNIT_REQUIRED)
    SizeChartUnitCode baseUnit,

    @Schema(description = "치수 항목(열). 1~20개. 이름과 순서는 템플릿 안에서 중복될 수 없다")
    @NotNull(message = SizeChartValidationMessage.COLUMNS_SIZE)
    @Size(min = SizeChartLimits.MIN_COLUMNS, max = SizeChartLimits.MAX_COLUMNS, message = SizeChartValidationMessage.COLUMNS_SIZE)
    List<@NotNull(message = SizeChartValidationMessage.COLUMN_REQUIRED) @Valid Column> columns
) {

    public SizeChartTemplateUpsertCommand toCommand() {
        return SizeChartTemplateUpsertCommand.builder()
            .name(SizeChartTextValues.normalize(name))
            .baseUnit(baseUnit.toDomain())
            .columns(columns.stream().map(Column::toCommand).toList())
            .build();
    }

    @Builder
    @Schema(description = "사이즈표 템플릿 항목")
    public record Column(

        @Schema(
            description = "항목 아이디. 이 템플릿을 쓰는 상품 사이즈표가 있을 때 기존 아이디를 보낸다. 없으면 생략한다. 문자열로 보내도 된다",
            example = "4611686018427387904", nullable = true
        )
        @Positive(message = SizeChartValidationMessage.COLUMN_ID_POSITIVE)
        Long columnId,

        @Schema(description = "항목 이름. 앞뒤 공백을 빼고 저장하며 뺀 뒤 1~30자다", example = "가슴")
        @SizeChartText(
            required = true,
            maxCodePoints = SizeChartLimits.MAX_COLUMN_NAME_CODE_POINTS,
            requiredMessage = SizeChartValidationMessage.COLUMN_NAME_REQUIRED,
            invalidCharacterMessage = SizeChartValidationMessage.COLUMN_NAME_INVALID_CHARACTER,
            tooLongMessage = SizeChartValidationMessage.COLUMN_NAME_SIZE_MAX
        )
        String name,

        @Schema(description = "항목 설명. 없으면 null. 앞뒤 공백을 빼고 200자 이하다", example = "겨드랑이 아래", nullable = true)
        @SizeChartText(
            maxCodePoints = SizeChartLimits.MAX_DESCRIPTION_CODE_POINTS,
            invalidCharacterMessage = SizeChartValidationMessage.COLUMN_DESCRIPTION_INVALID_CHARACTER,
            tooLongMessage = SizeChartValidationMessage.COLUMN_DESCRIPTION_SIZE_MAX
        )
        String description,

        @Schema(description = "항목 노출 순서 (0 이상)", example = "0")
        @NotNull(message = SizeChartValidationMessage.COLUMN_DISPLAY_ORDER_REQUIRED)
        @PositiveOrZero(message = SizeChartValidationMessage.COLUMN_DISPLAY_ORDER_MIN)
        Integer displayOrder
    ) {

        private SizeChartTemplateUpsertCommand.Column toCommand() {
            return SizeChartTemplateUpsertCommand.Column.builder()
                .columnId(columnId)
                .name(SizeChartTextValues.normalize(name))
                .description(SizeChartTextValues.normalize(description))
                .displayOrder(displayOrder)
                .build();
        }
    }
}
