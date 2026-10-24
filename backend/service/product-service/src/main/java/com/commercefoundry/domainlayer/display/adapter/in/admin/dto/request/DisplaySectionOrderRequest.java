package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.display.application.exception.DisplayValidationMessage;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "어드민 전시 섹션 순서 변경 요청")
public record DisplaySectionOrderRequest(

    @ArraySchema(
        arraySchema = @Schema(
            description = "섹션 아이디 전체를 새 순서대로 (최대 50개). 지금 있는 섹션 아이디를 빠짐없이 한 번씩 보내야 한다 — 누락 · 중복 · 모르는 아이디는 400(PRODUCT_057)이다"
        ),
        schema = @Schema(description = "섹션 아이디 — 문자열로 보내도 된다", example = "4611686018427387904")
    )
    @NotNull(message = DisplayValidationMessage.SECTION_IDS_REQUIRED)
    @Size(max = 50, message = DisplayValidationMessage.SECTION_IDS_SIZE_MAX)
    List<@NotNull(message = DisplayValidationMessage.SECTION_IDS_POSITIVE) @Positive(message = DisplayValidationMessage.SECTION_IDS_POSITIVE) Long> sectionIds
) {
}
