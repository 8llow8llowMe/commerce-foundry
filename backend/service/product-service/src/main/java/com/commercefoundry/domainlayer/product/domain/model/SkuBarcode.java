package com.commercefoundry.domainlayer.product.domain.model;

/** SKU 바코드 원문. 대소문자와 앞자리 0을 보존한다. */
public record SkuBarcode(String value) {

    public static final int MAX_LENGTH = 64;
    public static final String FORMAT = "[A-Za-z0-9_-]{1,64}";

    public SkuBarcode {
        if (value == null || !value.matches(FORMAT)) {
            throw new IllegalArgumentException("SKU 바코드 형식이 올바르지 않습니다.");
        }
    }

    public static SkuBarcode fromStoredOrNull(String value) {
        return value == null ? null : new SkuBarcode(value);
    }
}
