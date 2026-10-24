package com.commercefoundry.domainlayer.product.domain.model;

import java.util.Objects;

/**
 * 상품 미디어 (대표 이미지 · 갤러리 이미지 · 영상).
 *
 * @param url 이미지 URL. {@link ImageType#VIDEO} 면 영상 파일 URL 이다
 * @param width 원본 가로 px. 모르면 null
 * @param height 원본 세로 px. 모르면 null
 * @param displayOrder 같은 유형 안의 순서. {@link ImageType#THUMBNAIL} 은 항상 0
 * @param posterUrl 영상 포스터(재생 전 정지 이미지) URL. {@link ImageType#VIDEO} 면 필수, 그 밖에는 반드시 null
 */
public record ProductImage(
    long id, long productId, ImageType imageType, String url,
    Integer width, Integer height, String altText, int displayOrder, String posterUrl
) {

    public ProductImage {
        Objects.requireNonNull(imageType, "imageType");
        Objects.requireNonNull(url, "url");
        // DB check ck_product_image_poster_url 과 같은 규칙이다
        if (imageType.isVideo() != (posterUrl != null)) {
            throw new IllegalArgumentException("posterUrl is required only for VIDEO. imageId=" + id + ", imageType=" + imageType);
        }
    }
}
