package com.commercefoundry.domainlayer.look.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.validation.LookRequiredText;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.validation.LookText;
import com.commercefoundry.domainlayer.look.application.command.LookUpsertCommand;
import com.commercefoundry.domainlayer.look.application.exception.LookValidationMessage;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Builder;

/**
 * 어드민 룩 생성 · 수정 요청. 수정은 전체 교체라 생성과 본문이 같다 — 구성 상품은 배열 순서가 룩 안의 순서다.
 * 문구는 앞뒤 공백을 빼고 저장하며 빈 설명은 없는 값(null)으로 본다. <b>문구(제목 · 설명 · 대체 텍스트)는 평문이다</b> — 서버가 HTML 을 정제하지 않으므로
 * 화면은 텍스트 노드로만 렌더한다.
 *
 * <p>상품 중복 · 상품 존재 · 이미지 주소는 요청 검증이 아니라 Processor 가 본다({@code LOOK_003} · {@code LOOK_004} · {@code LOOK_005} · {@code LOOK_006}).
 */
@Builder
@Schema(description = "어드민 룩 생성 · 수정 요청 (수정은 전체 교체)")
public record LookUpsertRequest(

    @Schema(description = "제목 (1~100자, 제어 문자 불가). 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더)", example = "가을 나들이 룩")
    @LookRequiredText(message = LookValidationMessage.TITLE_REQUIRED)
    @Size(max = 100, message = LookValidationMessage.TITLE_SIZE_MAX)
    @LookText(message = LookValidationMessage.TITLE_INVALID_CHARACTER)
    String title,

    @Schema(
        description = "설명 (1000자 이하, 개행 · 탭 외 제어 문자 불가). 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)",
        example = "저고리와 치마를 같은 톤으로 맞춘 가을 코디", nullable = true
    )
    @Size(max = 1000, message = LookValidationMessage.DESCRIPTION_SIZE_MAX)
    @LookText(message = LookValidationMessage.DESCRIPTION_INVALID_CHARACTER, multiline = true)
    String description,

    @Schema(description = "대표 이미지")
    @NotNull(message = LookValidationMessage.IMAGE_REQUIRED)
    @Valid
    LookImageRequest image,

    @Schema(description = "고객 노출 여부. false 면 고객 화면에서 숨긴다", example = "true")
    @NotNull(message = LookValidationMessage.VISIBLE_REQUIRED)
    Boolean visible,

    @ArraySchema(
        arraySchema = @Schema(
            description = "구성 상품 아이디 (1~20개, 배열 순서 = 구성 순서). 같은 상품 두 번은 400(LOOK_003), 없는 상품은 400(LOOK_004)이다. 숨김 · 판매 종료 상품도 넣을 수 있다"
        ),
        schema = @Schema(description = "상품 아이디 — 문자열로 보내도 된다", example = "4611686018427387904")
    )
    @NotNull(message = LookValidationMessage.PRODUCT_IDS_REQUIRED)
    @Size(min = 1, max = 20, message = LookValidationMessage.PRODUCT_IDS_SIZE)
    List<@NotNull(message = LookValidationMessage.PRODUCT_ID_REQUIRED) @Positive(message = LookValidationMessage.PRODUCT_ID_POSITIVE) Long> productIds
) {

    public LookUpsertCommand toCommand() {
        return LookUpsertCommand.builder()
            .title(LookTextValues.normalize(title))
            .description(LookTextValues.normalize(description))
            .image(image.toCommand())
            .visible(visible)
            .productIds(productIds)
            .build();
    }
}
