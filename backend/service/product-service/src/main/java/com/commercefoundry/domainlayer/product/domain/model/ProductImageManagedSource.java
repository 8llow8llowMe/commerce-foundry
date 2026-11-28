package com.commercefoundry.domainlayer.product.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 이미 공개 확정된 상품 미디어 원본(#359 C1, D15) — 미디어 업로드 확정(#9)이 만든 {@code products/media/{actorId}/{UUID}/{SHA-256}.{ext}} 키와,
 * 그 객체를 처음 확인했을 때 고정한 ETag · 크기 · 형식이다. AI 상품 초안을 상품으로 적용할 때 초안의 이미지를 상품 이미지로 복사하는 입력이다.
 *
 * <p>임시 업로드 키({@link ProductImageUploadKey})와 달리 어드민 소유를 키로 다시 보지 않는다 — 키는 초안을 저장할 때 이미 검증 · 고정했고, 이 값은 어드민 요청
 * 본문에서 오지 않고 서버가 초안에서 만든다. 복사는 고정한 ETag 조건으로만 한다 — 그 사이 원본이 바뀌었으면 붙이지 않는다.
 *
 * @param key 관리 키
 * @param eTag 고정한 ETag (따옴표 포함 원문)
 * @param length 고정한 바이트 수
 * @param format 고정한 형식 — 키 확장자와 같다
 */
public record ProductImageManagedSource(String key, String eTag, long length, ProductImageFormat format) {

    private static final Pattern KEY = Pattern.compile(
        "products/media/[1-9][0-9]{0,18}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{64}\\.(jpg|png|webp)"
    );

    public ProductImageManagedSource {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(eTag, "eTag");
        Objects.requireNonNull(format, "format");
        if (!KEY.matcher(key).matches() || !key.endsWith("." + format.getExtension())) {
            throw new IllegalArgumentException("상품 미디어 관리 키가 아니거나 형식과 확장자가 다릅니다.");
        }
        if (eTag.isBlank() || length <= 0 || length > ProductImageFormat.MAX_BYTES) {
            throw new IllegalArgumentException("고정한 ETag · 크기가 올바르지 않습니다.");
        }
    }

    /** 키 · ETag 는 로그에 싣지 않는다. */
    @Override
    public String toString() {
        return "ProductImageManagedSource[format=" + format + ", length=" + length + "]";
    }
}
