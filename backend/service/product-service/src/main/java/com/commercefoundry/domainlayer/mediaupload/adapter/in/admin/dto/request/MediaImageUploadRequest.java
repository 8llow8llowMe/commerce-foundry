package com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadValidationMessage;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaImageFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "전시 · 룩 공용 이미지 업로드 발급")
public record MediaImageUploadRequest(
    @Schema(description = "JPEG · PNG · WebP MIME", example = "image/jpeg")
    @NotNull(message = MediaUploadValidationMessage.TYPE_REQUIRED)
    @Pattern(regexp = "(?i)\\s*image/(jpeg|png|webp)\\s*", message = MediaUploadValidationMessage.TYPE_FORMAT)
    String contentType,

    @Schema(description = "파일 바이트 수", example = "1024")
    @NotNull(message = MediaUploadValidationMessage.LENGTH_REQUIRED)
    @Min(value = 1, message = MediaUploadValidationMessage.LENGTH_MIN)
    @Max(value = MediaImageFormat.MAX_BYTES, message = MediaUploadValidationMessage.LENGTH_MAX)
    Long contentLength
) {}
