package com.commercefoundry.domainlayer.product.domain.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 어드민이 올릴 수 있는 상품 이미지 형식 (#9 설계 — JPEG · PNG · WebP, 10MB). SVG · GIF · HTML 은 받지 않는다(스크립트 · 애니메이션).
 * 실제 바이트 판별(magic bytes)은 첨부 때 스토리지 어댑터가 한다 — 이 enum 은 업로드 발급의 선언(Content-Type)과 키 확장자를 정한다.
 */
@Getter
@RequiredArgsConstructor
public enum ProductImageFormat {

    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    /** 한 장 상한 — 10MiB. */
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    private final String contentType;
    private final String extension;

    /** 대소문자 · 앞뒤 공백 무시. 매개변수({@code ; charset=})가 붙은 값은 받지 않는다 — 서명한 Content-Type 과 브라우저가 보내는 값이 같아야 한다. */
    public static Optional<ProductImageFormat> ofContentType(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        String normalized = contentType.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(format -> format.contentType.equals(normalized)).findFirst();
    }

    public static Optional<ProductImageFormat> ofExtension(String extension) {
        return Arrays.stream(values()).filter(format -> format.extension.equals(extension)).findFirst();
    }
}
