package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;

/**
 * 상품 미디어 (대표 이미지 · 갤러리 이미지 · 갤러리 영상 공통).
 *
 * @param imageType 미디어 유형. 화면 구분(이미지 · 영상)은 Presenter 가 이 값으로 정한다
 * @param url 이미지 URL. 영상이면 영상 파일 URL
 * @param width 원본 가로 px. 모르면 null
 * @param height 원본 세로 px. 모르면 null
 * @param posterUrl 영상 포스터 이미지 URL. 영상이 아니면 null
 */
public record ProductImageInfo(
    long imageId, ImageType imageType, String url, Integer width, Integer height, String altText, int displayOrder, String posterUrl
) {

    public static ProductImageInfo from(ProductImage image) {
        return new ProductImageInfo(
            image.id(), image.imageType(), image.url(), image.width(), image.height(), image.altText(), image.displayOrder(), image.posterUrl()
        );
    }
}
