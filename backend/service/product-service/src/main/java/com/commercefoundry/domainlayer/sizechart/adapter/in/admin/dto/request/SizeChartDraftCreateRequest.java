package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(description = "텍스트 사이즈표 초안 생성 요청")
public record SizeChartDraftCreateRequest(
    @Schema(description = "사이즈표 템플릿 아이디", example = "4611686018427387904")
    @NotNull(message = SizeChartDraftValidationMessage.TEMPLATE_ID)
    @Positive(message = SizeChartDraftValidationMessage.TEMPLATE_ID)
    Long templateId,

    @Schema(description = "입력 방식. 현재 TEXT만 지원합니다", example = "TEXT")
    @NotNull(message = SizeChartDraftValidationMessage.MODE)
    @Pattern(regexp = "TEXT", message = SizeChartDraftValidationMessage.MODE)
    String mode,

    @Schema(description = "운영자가 선택한 치수 텍스트", example = "S 가슴 50 총장 60")
    @NotBlank(message = SizeChartDraftValidationMessage.SOURCE)
    @Size(max = 12000, message = SizeChartDraftValidationMessage.SOURCE)
    String sourceText
) {
    public SizeChartDraftCreateCommand toCommand(long productId, long actorId, UUID key) {
        return new SizeChartDraftCreateCommand(productId, templateId, actorId, key, mode, sourceText);
    }

    @Override
    public String toString() {
        return "SizeChartDraftCreateRequest[redacted]";
    }
}
