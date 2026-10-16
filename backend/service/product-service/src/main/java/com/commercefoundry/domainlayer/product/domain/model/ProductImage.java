package com.commercefoundry.domainlayer.product.domain.model;

import java.util.Objects;

/**
 * @param width 원본 가로 px. 모르면 null
 * @param height 원본 세로 px. 모르면 null
 * @param displayOrder 같은 유형 안의 순서. {@link ImageType#THUMBNAIL} 은 항상 0
 */
public record ProductImage(
    long id, long productId, ImageType imageType, String url,
    Integer width, Integer height, String altText, int displayOrder
) {

    public ProductImage {
        Objects.requireNonNull(imageType, "imageType");
        Objects.requireNonNull(url, "url");
    }
}
