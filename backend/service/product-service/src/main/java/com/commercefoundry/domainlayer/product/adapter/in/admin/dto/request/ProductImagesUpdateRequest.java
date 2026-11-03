package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.product.application.command.ProductImagesUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductEditValidationMessage;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.util.List;

@Schema(description = "상품 정지 이미지 전체 교체 요청. VIDEO는 입력 없이 보존한다")
public record ProductImagesUpdateRequest(
    @Schema(description = "편집용 상세의 상품 버전", example = "0")
    @NotNull(message = ProductEditValidationMessage.VERSION_REQUIRED_OR_MIN)
    @PositiveOrZero(message = ProductEditValidationMessage.VERSION_REQUIRED_OR_MIN) Long version,

    @Schema(description = "정지 이미지 최대 8장. 빈 배열은 정지 이미지 연결 전체 삭제")
    @NotNull(message = ProductValidationMessage.PRODUCT_IMAGES_INVALID)
    @Size(max = 8, message = ProductValidationMessage.PRODUCT_IMAGES_INVALID)
    List<@NotNull(message = ProductValidationMessage.PRODUCT_IMAGES_INVALID) @Valid Image> images
) {
    public ProductImagesUpdateCommand toCommand(long productId, long actorMemberId) {
        return new ProductImagesUpdateCommand(productId, version, actorMemberId, images.stream()
            .map(i -> new ProductImagesUpdateCommand.Image(i.imageId(), i.uploadKey(), i.imageType(),
                i.altText() == null || i.altText().isBlank() ? null : i.altText().strip())).toList());
    }

    @Schema(description = "기존 imageId 또는 신규 uploadKey 중 하나만 입력")
    public record Image(
        @Schema(description = "이 상품의 기존 정지 이미지 ID", nullable = true)
        @Positive(message = ProductValidationMessage.PRODUCT_IMAGES_INVALID) Long imageId,

        @Schema(description = "현재 어드민에게 발급된 업로드 키", nullable = true)
        @Size(max = 100, message = ProductValidationMessage.PRODUCT_IMAGES_INVALID) String uploadKey,

        @Schema(description = "THUMBNAIL 또는 GALLERY")
        @NotNull(message = ProductValidationMessage.PRODUCT_IMAGES_INVALID) ImageType imageType,

        @Schema(description = "200자 이하 대체 텍스트", nullable = true)
        @Size(max = 200, message = ProductValidationMessage.PRODUCT_IMAGES_INVALID)
        @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductValidationMessage.PRODUCT_IMAGES_INVALID) String altText
    ) { }
}
