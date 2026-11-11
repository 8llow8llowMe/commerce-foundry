package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartImageLimits;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Schema(description = "사이즈표 초안 생성 요청. TEXT는 치수 텍스트, IMAGE는 확인·승인한 상품 이미지로 만듭니다")
public record SizeChartDraftCreateRequest(
    @Schema(description = "사이즈표 템플릿 아이디", example = "4611686018427387904")
    @NotNull(message = SizeChartDraftValidationMessage.TEMPLATE_ID)
    @Positive(message = SizeChartDraftValidationMessage.TEMPLATE_ID)
    Long templateId,

    @Schema(description = "입력 방식. TEXT 또는 IMAGE. IMAGE는 기능이 열린 환경에서만 받습니다", example = "TEXT", allowableValues = {"TEXT", "IMAGE"})
    @NotNull(message = SizeChartDraftValidationMessage.MODE)
    @Pattern(regexp = "TEXT|IMAGE", message = SizeChartDraftValidationMessage.MODE)
    String mode,

    @Schema(description = "운영자가 선택한 치수 텍스트. TEXT일 때 필수이고 IMAGE일 때는 보내지 않습니다", example = "S 가슴 50 총장 60")
    @Size(max = 12000, message = SizeChartDraftValidationMessage.SOURCE)
    String sourceText,

    @Schema(description = "IMAGE일 때 확인·승인한 이미지 1~4장. 순서대로 생성에 씁니다. TEXT일 때는 보내지 않습니다")
    @Size(max = SizeChartImageLimits.MAX_IMAGES, message = SizeChartDraftValidationMessage.IMAGES)
    List<@NotNull(message = SizeChartDraftValidationMessage.IMAGES) @Valid Image> images,

    @Schema(description = "IMAGE일 때 이미지에 고객 개인정보가 없음을 운영자가 확인했는지. IMAGE에서는 true여야 합니다", example = "true")
    Boolean personalInfoRemovedConfirmed
) {
    @Schema(description = "승인한 사이즈표 이미지")
    public record Image(
        @Schema(description = "이미지 후보 목록의 이미지 아이디", example = "IMG-4611686018427387905")
        @NotNull(message = SizeChartDraftValidationMessage.IMAGE_SOURCE_ID)
        @Pattern(regexp = SizeChartDraftValidationMessage.IMAGE_SOURCE_ID_PATTERN, message = SizeChartDraftValidationMessage.IMAGE_SOURCE_ID)
        String sourceId,

        @Schema(description = "이미지 확인 결과로 받은 확인값(SHA-256 소문자 16진수 64자)", example = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
        @NotNull(message = SizeChartDraftValidationMessage.IMAGE_HASH)
        @Pattern(regexp = "[0-9a-f]{64}", message = SizeChartDraftValidationMessage.IMAGE_HASH)
        String approvedContentHash
    ) {
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = SizeChartDraftValidationMessage.SOURCE)
    public boolean isTextSourcePresent() {
        return !"TEXT".equals(mode) || sourceText != null && !sourceText.isBlank();
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = SizeChartDraftValidationMessage.IMAGES_NOT_ALLOWED)
    public boolean isTextWithoutImages() {
        return !"TEXT".equals(mode) || images == null || images.isEmpty();
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = SizeChartDraftValidationMessage.SOURCE_NOT_ALLOWED)
    public boolean isImageWithoutText() {
        return !"IMAGE".equals(mode) || sourceText == null;
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = SizeChartDraftValidationMessage.IMAGES)
    public boolean isImageSelectionValid() {
        if (!"IMAGE".equals(mode)) {
            return true;
        }
        if (images == null || images.isEmpty() || images.size() > SizeChartImageLimits.MAX_IMAGES || images.stream().anyMatch(Objects::isNull)) {
            return false;
        }
        return new HashSet<>(images.stream().map(Image::sourceId).toList()).size() == images.size();
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = SizeChartDraftValidationMessage.PERSONAL_INFO)
    public boolean isPersonalInfoConfirmed() {
        return !"IMAGE".equals(mode) || Boolean.TRUE.equals(personalInfoRemovedConfirmed);
    }

    public SizeChartDraftCreateCommand toCommand(long productId, long actorId, UUID key) {
        List<SizeChartDraftCreateCommand.Image> commandImages = images == null ? List.of()
            : images.stream().map(image -> new SizeChartDraftCreateCommand.Image(image.sourceId(), image.approvedContentHash())).toList();
        return new SizeChartDraftCreateCommand(productId, templateId, actorId, key, mode, sourceText, commandImages,
            Boolean.TRUE.equals(personalInfoRemovedConfirmed));
    }

    @Override
    public String toString() {
        return "SizeChartDraftCreateRequest[redacted]";
    }
}
