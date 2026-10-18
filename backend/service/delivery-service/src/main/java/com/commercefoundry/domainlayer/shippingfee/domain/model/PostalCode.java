package com.commercefoundry.domainlayer.shippingfee.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 국내 우편번호 (국가기초구역 번호, 숫자 5자리).
 *
 * <p>자릿수가 고정이라 문자열 비교가 곧 숫자 비교다 — 앞자리 0({@code 01000})을 잃지 않게 숫자로 바꾸지 않는다.
 */
public record PostalCode(String value) implements Comparable<PostalCode> {

    private static final Pattern FORMAT = Pattern.compile("^[0-9]{5}$");

    public PostalCode {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("우편번호는 숫자 5자리여야 합니다: " + value);
        }
    }

    @Override
    public int compareTo(PostalCode other) {
        return value.compareTo(other.value);
    }

    public boolean isAfter(PostalCode other) {
        return compareTo(other) > 0;
    }

    @Override
    public String toString() {
        return value;
    }
}
