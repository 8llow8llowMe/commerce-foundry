package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.validation.DisplayText;
import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import com.commercefoundry.domainlayer.display.application.exception.DisplayValidationMessage;
import com.commercefoundry.domainlayer.display.domain.model.DisplayMediaType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * 전시 미디어 한 개. 주소는 스토어 미디어 저장소의 공개 주소 아래만 받는다 — 판정은 서버 설정을 봐야 해서 요청 검증이 아니라 Processor 가 한다
 * ({@code PRODUCT_054} · {@code PRODUCT_055}). 주소 · 문구는 앞뒤 공백을 빼고 저장하며, 빈 값은 없는 값(null)으로 본다.
 *
 * <p>교차 검증은 {@code @AssertTrue} 메서드다. 오류의 {@code field} 는 메서드 이름에서 온다({@code posterShapeValid}).
 */
@Builder
@Schema(description = "전시 미디어 (이미지 · 영상)")
public record DisplayMediaUpsertRequest(

    @Schema(description = "미디어 종류 — IMAGE · VIDEO", example = "IMAGE")
    @NotNull(message = DisplayValidationMessage.MEDIA_TYPE_REQUIRED)
    DisplayMediaType mediaType,

    @Schema(description = "PC 주소 (500자 이하) — 스토어 미디어 저장소의 공개 주소 아래여야 한다", example = "https://cdn.example.com/displays/hero-pc.jpg")
    @NotBlank(message = DisplayValidationMessage.MEDIA_PC_URL_REQUIRED)
    @Size(max = 500, message = DisplayValidationMessage.MEDIA_URL_SIZE_MAX)
    String pcUrl,

    @Schema(description = "모바일 주소 (500자 이하). 없으면 화면이 pcUrl 을 쓴다", example = "https://cdn.example.com/displays/hero-mobile.jpg", nullable = true)
    @Size(max = 500, message = DisplayValidationMessage.MEDIA_URL_SIZE_MAX)
    String mobileUrl,

    @Schema(description = "영상 포스터(PC, 500자 이하). VIDEO 면 필수, IMAGE 면 보내지 않는다", nullable = true)
    @Size(max = 500, message = DisplayValidationMessage.MEDIA_URL_SIZE_MAX)
    String pcPosterUrl,

    @Schema(description = "영상 포스터(모바일, 500자 이하). VIDEO 만 — 없으면 pcPosterUrl 을 쓴다", nullable = true)
    @Size(max = 500, message = DisplayValidationMessage.MEDIA_URL_SIZE_MAX)
    String mobilePosterUrl,

    @Schema(description = "대체 텍스트 (200자 이하, 제어 문자 불가). 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", example = "가을 생활한복 착용 사진", nullable = true)
    @Size(max = 200, message = DisplayValidationMessage.MEDIA_ALT_TEXT_SIZE_MAX)
    @DisplayText(message = DisplayValidationMessage.TEXT_INVALID_CHARACTER)
    String altText
) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = DisplayValidationMessage.MEDIA_POSTER_SHAPE_INVALID)
    public boolean isPosterShapeValid() {
        if (mediaType == null) {
            return true;
        }
        if (mediaType == DisplayMediaType.VIDEO) {
            return TextValues.normalize(pcPosterUrl) != null;
        }
        return TextValues.normalize(pcPosterUrl) == null && TextValues.normalize(mobilePosterUrl) == null;
    }

    public DisplaySectionUpsertCommand.Media toCommand() {
        return DisplaySectionUpsertCommand.Media.builder()
            .mediaType(mediaType)
            .pcUrl(TextValues.normalize(pcUrl))
            .mobileUrl(TextValues.normalize(mobileUrl))
            .pcPosterUrl(TextValues.normalize(pcPosterUrl))
            .mobilePosterUrl(TextValues.normalize(mobilePosterUrl))
            .altText(TextValues.normalize(altText))
            .build();
    }
}
