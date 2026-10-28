package com.commercefoundry.domainlayer.look.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.validation.LookRequiredText;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.validation.LookText;
import com.commercefoundry.domainlayer.look.application.command.LookUpsertCommand;
import com.commercefoundry.domainlayer.look.application.exception.LookValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * 룩 대표 이미지. 주소는 스토어 미디어 저장소의 공개 주소 아래만 받는다 — 판정은 서버 설정을 봐야 해서 요청 검증이 아니라 Processor 가 한다
 * ({@code LOOK_005} · {@code LOOK_006}). 주소 · 문구는 앞뒤 공백을 빼고 저장하며, 빈 대체 텍스트는 없는 값(null)으로 본다.
 */
@Builder
@Schema(description = "룩 대표 이미지")
public record LookImageRequest(

    @Schema(description = "이미지 주소 (500자 이하) — 스토어 미디어 저장소의 공개 주소 아래여야 한다", example = "https://cdn.example.com/looks/autumn.jpg")
    @LookRequiredText(message = LookValidationMessage.IMAGE_URL_REQUIRED)
    @Size(max = 500, message = LookValidationMessage.IMAGE_URL_SIZE_MAX)
    String url,

    @Schema(description = "원본 가로 px (양수). 모르면 보내지 않는다", example = "1200", nullable = true)
    @Positive(message = LookValidationMessage.IMAGE_WIDTH_POSITIVE)
    Integer width,

    @Schema(description = "원본 세로 px (양수). 모르면 보내지 않는다", example = "1600", nullable = true)
    @Positive(message = LookValidationMessage.IMAGE_HEIGHT_POSITIVE)
    Integer height,

    @Schema(description = "대체 텍스트 (200자 이하, 제어 문자 불가). 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더)", example = "가을 생활한복 코디", nullable = true)
    @Size(max = 200, message = LookValidationMessage.IMAGE_ALT_TEXT_SIZE_MAX)
    @LookText(message = LookValidationMessage.IMAGE_ALT_TEXT_INVALID_CHARACTER)
    String altText
) {

    public LookUpsertCommand.Image toCommand() {
        return LookUpsertCommand.Image.builder()
            .url(LookTextValues.normalize(url))
            .width(width)
            .height(height)
            .altText(LookTextValues.normalize(altText))
            .build();
    }
}
