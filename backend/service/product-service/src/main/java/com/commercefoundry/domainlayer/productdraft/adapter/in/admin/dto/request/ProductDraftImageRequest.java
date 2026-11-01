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
 * 초안 근거 이미지. 주소는 스토어 미디어 저장소의 공개 주소 아래만 받는다 — 판정은 서버 설정을 봐야 해서 Processor 가 한다({@code PRODUCT_DRAFT_030} · {@code 032}).
 * 업로드 키(#9 이미지 업로드)는 그 API 가 생긴 뒤 받는다.
 */
@Builder
@Schema(description = "초안 근거 이미지")
public record ProductDraftImageRequest(

    @Schema(description = "이미지 주소 (500자 이하) — 스토어 미디어 저장소의 공개 주소 아래여야 한다", example = "https://cdn.example.com/media/drafts/a.jpg")
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
