package com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadValidationMessage;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "메인 전시 영상 업로드 발급")
public record MediaVideoUploadRequest(
    @Schema(description = "MP4 MIME. video/mp4 만 받는다", example = "video/mp4")
    @NotNull(message = MediaUploadValidationMessage.VIDEO_TYPE_REQUIRED)
    @Pattern(regexp = "(?i)\\s*video/mp4\\s*", message = MediaUploadValidationMessage.VIDEO_TYPE_FORMAT)
    String contentType,

    @Schema(description = "파일 바이트 수 (1바이트~100MiB)", example = "10485760")
    @NotNull(message = MediaUploadValidationMessage.LENGTH_REQUIRED)
    @Min(value = 1, message = MediaUploadValidationMessage.LENGTH_MIN)
    @Max(value = MediaKind.VIDEO_MAX_BYTES, message = MediaUploadValidationMessage.VIDEO_LENGTH_MAX)
    Long contentLength
) {}
