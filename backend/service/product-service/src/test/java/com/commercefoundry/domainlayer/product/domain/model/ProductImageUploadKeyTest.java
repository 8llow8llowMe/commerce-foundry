package com.commercefoundry.domainlayer.product.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 임시 업로드 키 — 발급한 어드민이 키에 묶이고, 정규형만 읽힌다(경로 이동 · 다른 접두어 · 모르는 확장자 · 대문자 UUID 거절). */
class ProductImageUploadKeyTest {

    private static final UUID TOKEN = UUID.fromString("3f2a1c4e-8b7d-4e2a-9c1f-5a6b7c8d9e0f");

    @Test
    @DisplayName("키는 uploads/{memberId}/{uuid}.{확장자} 이고 다시 읽으면 같은 키다 — 발급한 어드민만 주인이다")
    void roundTrip() {
        ProductImageUploadKey key = new ProductImageUploadKey(100L, TOKEN, ProductImageFormat.WEBP);

        assertThat(key.value()).isEqualTo("uploads/100/3f2a1c4e-8b7d-4e2a-9c1f-5a6b7c8d9e0f.webp");
        assertThat(ProductImageUploadKey.parse(key.value())).contains(key);
        assertThat(key.isOwnedBy(100L)).isTrue();
        assertThat(key.isOwnedBy(101L)).isFalse();
    }

    @Test
    @DisplayName("새 키는 매번 다른 UUID 다")
    void issueIsUnique() {
        assertThat(ProductImageUploadKey.issue(100L, ProductImageFormat.JPEG)).isNotEqualTo(ProductImageUploadKey.issue(100L, ProductImageFormat.JPEG));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "uploads/100/3F2A1C4E-8B7D-4E2A-9C1F-5A6B7C8D9E0F.jpg",
        "uploads/100/../200/3f2a1c4e-8b7d-4e2a-9c1f-5a6b7c8d9e0f.jpg",
        "products/100/3f2a1c4e-8b7d-4e2a-9c1f-5a6b7c8d9e0f.jpg",
        "uploads/0/3f2a1c4e-8b7d-4e2a-9c1f-5a6b7c8d9e0f.jpg",
        "uploads/100/3f2a1c4e-8b7d-4e2a-9c1f-5a6b7c8d9e0f.gif",
        "uploads/100/3f2a1c4e-8b7d-4e2a-9c1f-5a6b7c8d9e0f.jpg?x=1",
        "uploads/99999999999999999999/3f2a1c4e-8b7d-4e2a-9c1f-5a6b7c8d9e0f.jpg",
        ""
    })
    @DisplayName("정규형이 아닌 키는 읽지 않는다")
    void rejectsNonCanonical(String value) {
        assertThat(ProductImageUploadKey.parse(value)).isEmpty();
    }

    @Test
    @DisplayName("Content-Type 은 대소문자 · 앞뒤 공백만 봐 준다 — 매개변수가 붙은 값 · 다른 형식은 받지 않는다")
    void contentType() {
        assertThat(ProductImageFormat.ofContentType(" Image/JPEG ")).contains(ProductImageFormat.JPEG);
        assertThat(ProductImageFormat.ofContentType("image/png; charset=binary")).isEmpty();
        assertThat(ProductImageFormat.ofContentType("image/svg+xml")).isEmpty();
        assertThat(ProductImageFormat.ofContentType("image/gif")).isEmpty();
        assertThat(ProductImageFormat.ofContentType(null)).isEmpty();
    }
}
