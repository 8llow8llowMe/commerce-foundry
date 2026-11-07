package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.Set;

@Schema(description = "검토한 사이즈표 초안 적용 요청")
public record SizeChartDraftApplyRequest(
    @Schema(description = "검토한 초안 버전", example = "1")
    @NotNull(message = SizeChartDraftValidationMessage.VERSION)
    @PositiveOrZero(message = SizeChartDraftValidationMessage.VERSION)
    Long expectedDraftVersion,

    @Schema(description = "운영자가 빠진 치수를 보완한 사이즈표 전체")
    @NotNull(message = SizeChartDraftValidationMessage.CHART)
    @Valid
    ProductSizeChartUpsertRequest correctedChart,

    @Schema(description = "운영자가 확인한 서버 발급 경고 아이디. 경고가 없으면 빈 배열")
    @NotNull(message = SizeChartDraftValidationMessage.ACK)
    @Size(max = 1800, message = SizeChartDraftValidationMessage.ACK)
    Set<@NotBlank(message = SizeChartDraftValidationMessage.ACK) @Size(max = 100, message = SizeChartDraftValidationMessage.ACK) String> acknowledgedWarningIds
) {
}
