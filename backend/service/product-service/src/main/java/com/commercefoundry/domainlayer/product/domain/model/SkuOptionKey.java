package com.commercefoundry.domainlayer.product.domain.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SKU 의 옵션 값 조합 키 — 옵션 값 아이디를 오름차순 · 중복 없이 {@code ,} 로 이은 문자열이다 ({@code "11,23"}).
 * 옵션이 없는 상품의 SKU 는 빈 문자열이다.
 *
 * <p><b>키를 만들고 읽는 곳은 이 클래스뿐이다.</b> 조합 키는 {@code sku (product_id, option_value_key)} 유니크 키이자
 * 상세 화면이 고른 옵션으로 SKU 를 찾는 기준이라, 정렬이나 구분자가 한 곳에서라도 어긋나면 같은 조합이 다른 SKU 로 갈라진다.
 * 저장된 값은 {@link #fromStored} 로만 읽는다.
 */
public final class SkuOptionKey {

    /** 상품당 옵션 그룹 최대 개수 — 조합 키에 들어가는 옵션 값의 최대 개수이기도 하다. */
    public static final int MAX_GROUPS = 5;

    private static final String DELIMITER = ",";
    private static final Pattern TOKEN = Pattern.compile("[1-9][0-9]*");
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
     * DB 에 저장된 키를 읽는다. 저장값은 {@link #of} 가 만든 정규형 그대로여야 한다.
     *
     * <p>정렬이 안 됐거나 중복이 있는 값을 정규화해서 넘기지 않고 실패한다. 그런 행이 있다면 같은 조합이 다른 문자열로
     * {@code (product_id, option_value_key)} 유니크 키를 비켜 SKU 두 개로 저장됐을 수 있다 — 조용히 고쳐 읽으면 그 중복을 가린다.
     *
     * @throws IllegalStateException 형식 오류 · 정규형이 아님 · 조합 개수 초과. 모두 저장 데이터 오류다 (메시지에 저장값을 넣는다)
     */
    public static SkuOptionKey fromStored(String stored) {
        Objects.requireNonNull(stored, "stored");

        SkuOptionKey key;
        try {
            key = of(parse(stored));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("저장된 옵션 값 조합 키 형식이 아닙니다: \"%s\"".formatted(stored), exception);
        }
        if (!key.value.equals(stored)) {
            throw new IllegalStateException(
                "저장된 옵션 값 조합 키가 정규형(오름차순 · 중복 없음)이 아닙니다: \"%s\" (정규형 \"%s\")".formatted(stored, key.value)
            );
        }
        return key;
    }

    /**
     * 키 문자열을 옵션 값 아이디 목록으로 읽는다. 순서는 문자열 그대로다 — 정규화는 {@link #of} 가 한다.
     *
     * <p>조각은 0 으로 시작하지 않는 양의 십진수({@code [1-9][0-9]*})만 받는다. {@code "+5"} · {@code "05"} 처럼
     * 같은 아이디를 다르게 적은 문자열이 다른 키로 갈라지지 않게 한다.
     *
     * @return 빈 문자열이면 빈 목록
     * @throws IllegalArgumentException 형식에 맞지 않는 조각이 있을 때 ({@code "1,,2"}, {@code "05"}, {@code "+5"}, {@code "a"}) · long 범위를 넘을 때
     */
    public static List<Long> parse(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            return List.of();
        }

        List<Long> valueIds = new ArrayList<>();
        for (String token : value.split(DELIMITER, -1)) {
            if (!TOKEN.matcher(token).matches()) {
                throw new IllegalArgumentException("옵션 값 조합 키 형식이 아닙니다: " + value);
            }
            try {
                valueIds.add(Long.parseLong(token));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("옵션 값 아이디가 long 범위를 넘습니다: " + value, exception);
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
