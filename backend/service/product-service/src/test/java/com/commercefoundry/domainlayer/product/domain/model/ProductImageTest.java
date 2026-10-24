package com.commercefoundry.domainlayer.product.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProductImageTest {

    private static final String POSTER = "https://cdn.example.com/poster.jpg";

    @Test
    @DisplayName("영상은 포스터 이미지 URL 과 함께 만든다")
    void videoWithPoster() {
        ProductImage video = image(ImageType.VIDEO, POSTER);

        assertThat(video.posterUrl()).isEqualTo(POSTER);
        assertThat(video.imageType().isVideo()).isTrue();
    }

    @Test
    @DisplayName("포스터가 없는 영상은 거절한다 — DB check ck_product_image_poster_url 과 같다")
    void videoWithoutPosterRejected() {
        assertThatThrownBy(() -> image(ImageType.VIDEO, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("posterUrl");
    }

    @ParameterizedTest
    @EnumSource(value = ImageType.class, names = {"THUMBNAIL", "GALLERY"})
    @DisplayName("이미지에는 포스터가 없다 — 있으면 거절한다")
    void imageWithPosterRejected(ImageType imageType) {
        assertThat(image(imageType, null).posterUrl()).isNull();
        assertThat(imageType.isVideo()).isFalse();
        assertThatThrownBy(() -> image(imageType, POSTER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("posterUrl");
    }

    private static ProductImage image(ImageType imageType, String posterUrl) {
        return new ProductImage(1L, 7L, imageType, "https://cdn.example.com/1", null, null, null, 0, posterUrl);
    }
}
