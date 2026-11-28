package com.commercefoundry.domainlayer.product.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 고정한 상품 미디어 원본 (#359 C1). */
class ProductImageManagedSourceTest {

    static final String KEY = "products/media/7/0f8fad5b-d9cb-469f-a165-70867728950e/" + "a".repeat(64) + ".png";

    @Test
    @DisplayName("업로드 확정 미디어 키 · ETag · 크기 · 형식을 받고, 문자열에 키 · ETag 를 싣지 않는다")
    void accepts() {
        ProductImageManagedSource source = new ProductImageManagedSource(KEY, "\"etag-1\"", 12, ProductImageFormat.PNG);

        assertThat(source.key()).isEqualTo(KEY);
        assertThat(source.toString()).doesNotContain("products/media").doesNotContain("etag-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "uploads/7/0f8fad5b-d9cb-469f-a165-70867728950e.png",
        "products/7/8.png",
        "products/media/0/0f8fad5b-d9cb-469f-a165-70867728950e/aaaa.png",
        "products/media/7/0f8fad5b-d9cb-469f-a165-70867728950e/../x.png"
    })
    @DisplayName("업로드 확정 미디어 키가 아니면 만들 수 없다 — 임시 업로드 · 상품별 키 · 경로 조작")
    void rejectsOtherKeys(String key) {
        assertThatThrownBy(() -> new ProductImageManagedSource(key, "\"e\"", 12, ProductImageFormat.PNG)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("키 확장자와 형식이 다르거나 ETag 가 비었거나 크기가 1B~10MB 밖이면 만들 수 없다")
    void rejectsInconsistentPin() {
        assertThatThrownBy(() -> new ProductImageManagedSource(KEY, "\"e\"", 12, ProductImageFormat.JPEG)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductImageManagedSource(KEY, " ", 12, ProductImageFormat.PNG)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductImageManagedSource(KEY, "\"e\"", 0, ProductImageFormat.PNG)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductImageManagedSource(KEY, "\"e\"", ProductImageFormat.MAX_BYTES + 1, ProductImageFormat.PNG))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
