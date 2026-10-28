package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.display.application.exception.DisplayValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "저장하지 않을 전시 전체 초안")
public record DisplayPreviewRequest(

    @Schema(description = "섹션 전체 초안. 입력 순서대로, 최대 50개, 빈 배열 허용")
    @NotNull(message = DisplayValidationMessage.PREVIEW_SECTIONS_REQUIRED)
    @Size(max = 50, message = DisplayValidationMessage.PREVIEW_SECTIONS_SIZE_MAX)
    List<@NotNull(message = DisplayValidationMessage.PREVIEW_SECTION_REQUIRED) @Valid DisplaySectionUpsertRequest> sections
) {
}
