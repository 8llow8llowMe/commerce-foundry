package com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.dto.request;

import com.commercefoundry.domainlayer.sizechartgeneration.application.command.SizeChartGenerationRegisterCommand;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

public record SizeChartGenerationRegisterRequest(
    @Schema(description = "초안 식별자")
    @NotNull(message = SizeChartGenerationValidationMessage.DRAFT_ID)
    @Positive(message = SizeChartGenerationValidationMessage.DRAFT_ID)
    Long draftId,

    @Schema(description = "정규화한 입력 SHA-256")
    @NotNull(message = SizeChartGenerationValidationMessage.INPUT_HASH)
    @Pattern(regexp = "[a-f0-9]{64}", message = SizeChartGenerationValidationMessage.INPUT_HASH)
    String inputHash,

    @Schema(description = "검증된 요청 운영자 식별자")
    @NotNull(message = SizeChartGenerationValidationMessage.ACTOR)
    @Positive(message = SizeChartGenerationValidationMessage.ACTOR)
    Long requestedByMemberId,

    @Schema(description = "초안 만료 시각")
    @NotNull(message = SizeChartGenerationValidationMessage.EXPIRY)
    Instant expiresAt
) {
    public SizeChartGenerationRegisterCommand toCommand() {
        return new SizeChartGenerationRegisterCommand(draftId, inputHash, requestedByMemberId, expiresAt);
    }
}
