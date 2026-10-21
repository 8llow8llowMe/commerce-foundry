package com.commercefoundry.domainlayer.order.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 한 주문 줄의 맞춤 제작 입력 묶음. 줄의 동일성(SKU · 이 묶음의 {@link #hash()})을 정한다 — 같은 SKU 라도 입력이 다르면 다른 줄이고, 같으면 요청 안의 중복 줄이다.
 *
 * <p>장바구니({@code cart} 의 같은 이름 값)와 <b>같은 정규화 · 같은 해시 규칙</b>이다 — 규칙 정본은 {@code order-flow-guide.md} §2-1 "줄 동일성".
 * 장바구니 값을 import 하지 않고 여기 따로 둔다(컨텍스트 간 허용 목록 밖, {@code architecture-guide.md} §3). 둘 중 하나를 바꾸면 다른 하나도 바꾼다 —
 * 같은 입력의 해시를 고정값으로 두 테스트가 함께 붙잡는다.
 *
 * <ul>
 *   <li>키 · 값은 앞뒤 공백을 자르고 NFC 로 정규화한다({@link OrderCustomOption}). 순서만 바꿔 보내도 같은 해시다 — fieldKey 순으로 정렬해 만든다.</li>
 *   <li>표시 순서는 받은 순서다.</li>
 *   <li>입력이 없으면 해시는 빈 문자열이다.</li>
 * </ul>
 */
public final class OrderCustomOptions {

    public static final int MAX_SIZE = 20;

    private static final OrderCustomOptions EMPTY = new OrderCustomOptions(List.of());
    private static final Comparator<OrderCustomOption> BY_FIELD_KEY = Comparator.comparing(OrderCustomOption::fieldKey);

    private final List<OrderCustomOption> values;
    private final String hash;

    private OrderCustomOptions(List<OrderCustomOption> values) {
        this.values = List.copyOf(values);
        this.hash = hashOf(this.values);
    }

    public static OrderCustomOptions empty() {
        return EMPTY;
    }

    /**
     * @param values 표시 순서. null 이면 빈 묶음
     * @throws IllegalArgumentException fieldKey 가 중복되거나 {@value #MAX_SIZE}개를 넘을 때 — 요청 검증이 먼저 거른다
     */
    public static OrderCustomOptions of(List<OrderCustomOption> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY;
        }
        if (values.size() > MAX_SIZE) {
            throw new IllegalArgumentException("맞춤 제작 입력은 최대 %d개입니다: %d".formatted(MAX_SIZE, values.size()));
        }
        Set<String> fieldKeys = new HashSet<>();
        for (OrderCustomOption value : values) {
            Objects.requireNonNull(value, "customOption");
            if (!fieldKeys.add(value.fieldKey())) {
                throw new IllegalArgumentException("맞춤 제작 입력의 fieldKey 가 중복되었습니다: " + value.fieldKey());
            }
        }
        return new OrderCustomOptions(values);
    }

    public List<OrderCustomOption> values() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** SHA-256 소문자 hex 64자. 입력이 없으면 빈 문자열. */
    public String hash() {
        return hash;
    }

    /**
     * fieldKey 순으로 정렬해 칸마다 {@code <길이>:<fieldKey><길이>:<값>} 을 이은 UTF-8 바이트의 SHA-256. 길이를 앞에 붙여 경계가 모호하지 않게 한다
     * (예: {@code a=bc} 와 {@code ab=c} 가 같은 문자열이 되지 않게).
     */
    private static String hashOf(List<OrderCustomOption> values) {
        if (values.isEmpty()) {
            return "";
        }
        StringBuilder canonical = new StringBuilder();
        values.stream().sorted(BY_FIELD_KEY).forEach(value -> canonical
            .append(value.fieldKey().length()).append(':').append(value.fieldKey())
            .append(value.value().length()).append(':').append(value.value()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", exception);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OrderCustomOptions that && values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "OrderCustomOptions" + values;
    }
}
