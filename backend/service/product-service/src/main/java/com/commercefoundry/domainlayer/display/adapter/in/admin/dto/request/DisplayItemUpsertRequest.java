package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.validation.DisplayText;
import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import com.commercefoundry.domainlayer.display.application.exception.DisplayValidationMessage;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * 전시 아이템 한 개 — 미디어 또는 상품. 다른 유형의 칸은 보내지 않는다.
 *
 * <p>교차 검증은 {@code @AssertTrue} 메서드다. 오류의 {@code field} 는 메서드 이름에서 온다({@code mediaShapeValid} · {@code productShapeValid}).
 */
@Builder
@Schema(description = "전시 아이템 (미디어 또는 상품)")
public record DisplayItemUpsertRequest(

    @Schema(description = "아이템 유형 — MEDIA(media 필수) · PRODUCT(productId 필수)", example = "PRODUCT")
    @NotNull(message = DisplayValidationMessage.ITEM_TYPE_REQUIRED)
    DisplayItemType itemType,

    @Schema(description = "미디어. itemType 이 MEDIA 일 때만 보낸다", nullable = true)
    @Valid
    DisplayMediaUpsertRequest media,

    @Schema(
        description = "상품 아이디 — 문자열로 보내도 된다. itemType 이 PRODUCT 일 때만 보낸다. 없는 상품이면 400(PRODUCT_050), 숨긴 상품은 등록되고 고객 메인에서만 빠진다",
        example = "4611686018427387904", nullable = true
    )
    @Positive(message = DisplayValidationMessage.ITEM_PRODUCT_ID_POSITIVE)
    Long productId,

    @Schema(description = "아이템 설명 문구 (200자 이하, 제어 문자 불가). 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", example = "봄 신상", nullable = true)
    @Size(max = 200, message = DisplayValidationMessage.ITEM_CAPTION_SIZE_MAX)
    @DisplayText(message = DisplayValidationMessage.TEXT_INVALID_CHARACTER)
    String caption
) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = DisplayValidationMessage.ITEM_MEDIA_SHAPE_INVALID)
    public boolean isMediaShapeValid() {
        return itemType != DisplayItemType.MEDIA || media != null && productId == null;
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = DisplayValidationMessage.ITEM_PRODUCT_SHAPE_INVALID)
    public boolean isProductShapeValid() {
        return itemType != DisplayItemType.PRODUCT || productId != null && media == null;
    }

    public DisplaySectionUpsertCommand.Item toCommand() {
        return DisplaySectionUpsertCommand.Item.builder()
            .itemType(itemType)
            .media(media == null ? null : media.toCommand())
            .productId(productId)
            .caption(TextValues.normalize(caption))
            .build();
    }
}
