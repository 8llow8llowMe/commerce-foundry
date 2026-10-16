package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.domain.model.ProductImage;

/**
 * 상품 이미지 (대표 · 갤러리 공통).
 *
 * @param width 원본 가로 px. 모르면 null
 * @param height 원본 세로 px. 모르면 null
 */
public record ProductImageInfo(long imageId, String url, Integer width, Integer height, String altText, int displayOrder) {

    public static ProductImageInfo from(ProductImage image) {
        return new ProductImageInfo(image.id(), image.url(), image.width(), image.height(), image.altText(), image.displayOrder());
    }
}
