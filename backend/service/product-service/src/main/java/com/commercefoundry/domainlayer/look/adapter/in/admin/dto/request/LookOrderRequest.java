package com.commercefoundry.domainlayer.look.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.look.application.exception.LookValidationMessage;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "어드민 룩 순서 변경 요청")
public record LookOrderRequest(

    @ArraySchema(
        arraySchema = @Schema(
            description = "룩 아이디 전체를 새 순서대로 (최대 100개). 지금 있는 룩 아이디를 빠짐없이 한 번씩 보내야 한다 — 누락 · 중복 · 모르는 아이디는 400(LOOK_007)이다"
        ),
        schema = @Schema(description = "룩 아이디 — 문자열로 보내도 된다", example = "4611686018427387904")
    )
    @NotNull(message = LookValidationMessage.LOOK_IDS_REQUIRED)
    @Size(max = 100, message = LookValidationMessage.LOOK_IDS_SIZE_MAX)
    List<@NotNull(message = LookValidationMessage.LOOK_ID_ELEMENT_REQUIRED) @Positive(message = LookValidationMessage.LOOK_ID_ELEMENT_POSITIVE) Long> lookIds
) {
}
