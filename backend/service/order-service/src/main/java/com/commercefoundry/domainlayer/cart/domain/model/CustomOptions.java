package com.commercefoundry.domainlayer.cart.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 한 장바구니 줄의 맞춤 제작 입력 묶음. 줄의 동일성(회원 · SKU · 이 묶음의 {@link #hash()})을 정한다.
 *
 * <ul>
 *   <li>키 · 값은 앞뒤 공백을 자르고 NFC 로 정규화한다({@link CustomOption}). 같은 입력을 순서만 바꿔 보내도 같은 해시다 — 해시는 fieldKey 순으로 정렬해 만든다.</li>
 *   <li>표시 순서는 처음 담을 때 받은 순서다.</li>
 *   <li>입력이 없으면 해시는 빈 문자열이다 — 유니크 키 {@code (member_id, sku_id, custom_option_hash)} 에서 null 은 서로 다른 값이라 중복을 막지 못한다.</li>
 * </ul>
 */
public final class CustomOptions {

    public static final int MAX_SIZE = 20;

    private static final CustomOptions EMPTY = new CustomOptions(List.of());
    private static final Comparator<CustomOption> BY_FIELD_KEY = Comparator.comparing(CustomOption::fieldKey);

    private final List<CustomOption> values;
    private final String hash;

    private CustomOptions(List<CustomOption> values) {
        this.values = List.copyOf(values);
        this.hash = hashOf(this.values);
    }

    public static CustomOptions empty() {
        return EMPTY;
    }

    /**
     * @param values 표시 순서. null 이면 빈 묶음
     * @throws IllegalArgumentException fieldKey 가 중복되거나 {@value #MAX_SIZE}개를 넘을 때 — 요청 검증이 먼저 거른다
     */
    public static CustomOptions of(List<CustomOption> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY;
        }
        if (values.size() > MAX_SIZE) {
            throw new IllegalArgumentException("맞춤 제작 입력은 최대 %d개입니다: %d".formatted(MAX_SIZE, values.size()));
        }
        Set<String> fieldKeys = new HashSet<>();
        for (CustomOption value : values) {
            Objects.requireNonNull(value, "customOption");
            if (!fieldKeys.add(value.fieldKey())) {
                throw new IllegalArgumentException("맞춤 제작 입력의 fieldKey 가 중복되었습니다: " + value.fieldKey());
            }
        }
        return new CustomOptions(values);
    }

    public List<CustomOption> values() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** SHA-256 소문자 hex 64자. 입력이 없으면 빈 문자열. */
    public String hash() {
        return hash;
    }

    /** 같은 입력인가 — 해시로 본다. 행 아이디는 보지 않는다. */
    public boolean sameAs(CustomOptions other) {
        return hash.equals(other.hash);
    }

    /** 아직 아이디가 없는 칸에 아이디를 채운다. 줄을 새로 저장하기 전에 Processor 가 부른다. */
    public CustomOptions withIds(LongSupplier idGenerator) {
        return new CustomOptions(values.stream().map(value -> value.id() == null ? value.withId(idGenerator.getAsLong()) : value).toList());
    }

    /**
     * fieldKey 순으로 정렬해 칸마다 {@code <길이>:<fieldKey><길이>:<값>} 을 이은 UTF-8 바이트의 SHA-256. 길이를 앞에 붙여 경계가 모호하지 않게 한다
     * (예: {@code a=bc} 와 {@code ab=c} 가 같은 문자열이 되지 않게).
     */
    private static String hashOf(List<CustomOption> values) {
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
        return other instanceof CustomOptions that && values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "CustomOptions" + values;
    }
}
