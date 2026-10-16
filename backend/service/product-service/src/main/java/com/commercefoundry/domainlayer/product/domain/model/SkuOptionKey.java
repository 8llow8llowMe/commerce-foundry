package com.commercefoundry.domainlayer.product.domain.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * SKU 의 옵션 값 조합 키 — 옵션 값 아이디를 오름차순 · 중복 없이 {@code ,} 로 이은 문자열이다 ({@code "11,23"}).
 * 옵션이 없는 상품의 SKU 는 빈 문자열이다.
 *
 * <p><b>키를 만들고 읽는 곳은 이 클래스뿐이다.</b> 조합 키는 {@code sku (product_id, option_value_key)} 유니크 키이자
 * 상세 화면이 고른 옵션으로 SKU 를 찾는 기준이라, 정렬이나 구분자가 한 곳에서라도 어긋나면 같은 조합이 다른 SKU 로 갈라진다.
 */
public final class SkuOptionKey {

    /** 상품당 옵션 그룹 최대 개수 — 조합 키에 들어가는 옵션 값의 최대 개수이기도 하다. */
    public static final int MAX_GROUPS = 5;

    private static final String DELIMITER = ",";
    private static final SkuOptionKey EMPTY = new SkuOptionKey(List.of());

    private final List<Long> valueIds;
    private final String value;

    private SkuOptionKey(List<Long> valueIds) {
        this.valueIds = valueIds;
        this.value = String.join(DELIMITER, valueIds.stream().map(String::valueOf).toList());
    }

    /**
     * 옵션 값 아이디로 키를 만든다. 순서와 중복은 정규화한다.
     *
     * @throws IllegalArgumentException 아이디가 양수가 아니거나, 중복을 빼고 {@link #MAX_GROUPS} 개를 넘을 때
     */
    public static SkuOptionKey of(Collection<Long> valueIds) {
        Objects.requireNonNull(valueIds, "valueIds");
        valueIds.forEach(SkuOptionKey::requirePositive);
        List<Long> normalized = valueIds.stream().distinct().sorted().toList();
        if (normalized.size() > MAX_GROUPS) {
            throw new IllegalArgumentException("옵션 값은 %d개까지만 조합할 수 있습니다: %s".formatted(MAX_GROUPS, normalized));
        }
        return normalized.isEmpty() ? EMPTY : new SkuOptionKey(normalized);
    }

    /**
     * 저장된 키 문자열을 옵션 값 아이디 목록으로 읽는다. 순서는 문자열 그대로다 — 정규화는 {@link #of} 가 한다.
     *
     * @return 빈 문자열이면 빈 목록
     * @throws IllegalArgumentException 숫자가 아닌 조각이 있을 때 ({@code "1,,2"}, {@code "a"})
     */
    public static List<Long> parse(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            return List.of();
        }

        List<Long> valueIds = new ArrayList<>();
        for (String token : value.split(DELIMITER, -1)) {
            try {
                valueIds.add(Long.parseLong(token));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("옵션 값 조합 키 형식이 아닙니다: " + value, exception);
            }
        }
        return List.copyOf(valueIds);
    }

    /** 저장 · 비교에 쓰는 정규형 문자열. */
    public String value() {
        return value;
    }

    /** 오름차순 · 중복 없는 옵션 값 아이디. */
    public List<Long> valueIds() {
        return valueIds;
    }

    public boolean isEmpty() {
        return valueIds.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SkuOptionKey key && value.equals(key.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }

    private static void requirePositive(Long valueId) {
        if (valueId == null || valueId <= 0) {
            throw new IllegalArgumentException("옵션 값 아이디는 양수여야 합니다: " + valueId);
        }
    }
}
