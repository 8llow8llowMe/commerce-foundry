package com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.dto.request;

import com.commercefoundry.domainlayer.productdraftgeneration.application.command.ProductDraftGenerationRegisterCommand;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationValidationMessage;
import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationReceipt;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

public record ProductDraftGenerationRegisterRequest(
    @Schema(description = "초안 식별자")
    @NotNull(message = ProductDraftGenerationValidationMessage.DRAFT_ID)
    @Positive(message = ProductDraftGenerationValidationMessage.DRAFT_ID)
    Long draftId,

    @Schema(description = "생성 회차 (1~3, product 가 매긴다)", minimum = "1", maximum = "3")
    @NotNull(message = ProductDraftGenerationValidationMessage.GENERATION_NO)
    @Min(value = ProductDraftGenerationReceipt.MIN_GENERATION_NO, message = ProductDraftGenerationValidationMessage.GENERATION_NO)
    @Max(value = ProductDraftGenerationReceipt.MAX_GENERATION_NO, message = ProductDraftGenerationValidationMessage.GENERATION_NO)
    Integer generationNo,

    @Schema(description = "정규화한 입력 SHA-256 (소문자 hex 64자)")
    @NotNull(message = ProductDraftGenerationValidationMessage.INPUT_HASH)
    @Pattern(regexp = "[a-f0-9]{64}", message = ProductDraftGenerationValidationMessage.INPUT_HASH)
    String inputHash,

    @Schema(description = "검증된 요청 운영자 식별자")
    @NotNull(message = ProductDraftGenerationValidationMessage.ACTOR)
    @Positive(message = ProductDraftGenerationValidationMessage.ACTOR)
    Long actorMemberId,

    @Schema(description = "초안 만료 시각")
    @NotNull(message = ProductDraftGenerationValidationMessage.EXPIRY)
    Instant expiresAt
) {
    public ProductDraftGenerationRegisterCommand toCommand() {
        return new ProductDraftGenerationRegisterCommand(draftId, generationNo, inputHash, actorMemberId, expiresAt);
    }
}
