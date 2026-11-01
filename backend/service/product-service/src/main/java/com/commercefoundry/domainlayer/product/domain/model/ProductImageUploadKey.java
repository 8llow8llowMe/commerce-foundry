package com.commercefoundry.domainlayer.product.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 어드민 상품 이미지 임시 업로드 키 — {@code uploads/{memberId}/{uuid}.{ext}} (#9 F, D1 presigned PUT). 발급한 어드민 아이디를 키에 넣어 묶는다 —
 * 첨부(등록 · 수정)는 요청한 어드민의 키만 받는다(남이 받은 키를 추측 · 재사용해 붙이지 못하게). UUID 는 추측할 수 없고, 키 하나는 한 번 올리는 자리다.
 *
 * <p>{@code uploads/} 는 공개 읽기가 닫힌 임시 접두어이고 버킷 수명 규칙이 1일 뒤 지운다. 첨부할 때 {@code products/} 로 복사한다(F2).
 */
public record ProductImageUploadKey(long memberId, UUID token, ProductImageFormat format) {

    public static final String PREFIX = "uploads/";

    private static final Pattern FORMAT = Pattern.compile(
        "uploads/([1-9][0-9]{0,18})/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.([a-z]{3,4})"
    );

    public ProductImageUploadKey {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(format, "format");
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId 는 양수여야 합니다: " + memberId);
        }
    }

    /** 새 키 — 아무도 쓰지 않은 UUID 를 받는다. */
    public static ProductImageUploadKey issue(long memberId, ProductImageFormat format) {
        return new ProductImageUploadKey(memberId, UUID.randomUUID(), format);
    }

    /**
     * 본문의 키를 읽는다. 형식이 다르거나 확장자를 모르면 비어 있다 — 호출한 쪽이 "업로드 없음"(400)으로 답한다(어떤 칸이 틀렸는지 알리지 않는다).
     * UUID 는 소문자 표준형만 받는다 — 같은 키가 다른 문자열로 두 번 쓰이지 않게.
     */
    public static Optional<ProductImageUploadKey> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            long memberId = Long.parseLong(matcher.group(1));
            UUID token = UUID.fromString(matcher.group(2));
            return ProductImageFormat.ofExtension(matcher.group(3)).map(format -> new ProductImageUploadKey(memberId, token, format));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    /** 이 어드민이 발급받은 키인가. */
    public boolean isOwnedBy(long requestMemberId) {
        return memberId == requestMemberId;
    }

    public String value() {
        return PREFIX + memberId + "/" + token + "." + format.getExtension();
    }
}
