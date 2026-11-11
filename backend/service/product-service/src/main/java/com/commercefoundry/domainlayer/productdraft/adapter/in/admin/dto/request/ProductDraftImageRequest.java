package com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftValidationMessage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * 초안 근거 이미지. 주소는 미디어 업로드 확정(#9)이 돌려준 상품 미디어 공개 주소({@code products/media/**})만 받는다 — 판정은 서버 설정 · 저장소를 봐야 해서
 * Processor 가 한다({@code PRODUCT_DRAFT_030} · {@code 031} · {@code 032} · {@code 033} · {@code 036} · {@code 501}). 상품 이미지 아이디 입력은 받지 않는다(#359 D10).
 */
@Builder
@Schema(description = "초안 근거 이미지")
public record ProductDraftImageRequest(

    @Schema(description = "이미지 주소 (500자 이하) — 미디어 업로드 확정이 돌려준 공개 주소(products/media/...)여야 한다. JPEG · PNG · WebP, 5MB 이하",
        example = "https://cdn.example.com/products/media/7/0f8fad5b-d9cb-469f-a165-70867728950e/3b1f...e9.jpg")
    @NotBlank(message = ProductDraftValidationMessage.IMAGE_URL_REQUIRED)
    @Size(max = 500, message = ProductDraftValidationMessage.IMAGE_URL_SIZE_MAX)
    String url,

    @Schema(description = "이미지 유형 — THUMBNAIL(대표, 한 장) · GALLERY(갤러리). 영상(VIDEO) 등 다른 값은 400(PRODUCT_100)", example = "THUMBNAIL",
        allowableValues = {"THUMBNAIL", "GALLERY"})
    @NotNull(message = ProductDraftValidationMessage.IMAGE_TYPE_REQUIRED)
    ProductDraftImageType imageType,

    @Schema(description = "원본 가로 px (양수). 모르면 보내지 않는다", example = "1200", nullable = true)
    @Positive(message = ProductDraftValidationMessage.IMAGE_WIDTH_POSITIVE)
    Integer width,

    @Schema(description = "원본 세로 px (양수). 모르면 보내지 않는다", example = "1600", nullable = true)
    @Positive(message = ProductDraftValidationMessage.IMAGE_HEIGHT_POSITIVE)
    Integer height
) {

    public ProductDraftImage toDomain() {
        return new ProductDraftImage(url.strip(), imageType, width, height);
    }
}
