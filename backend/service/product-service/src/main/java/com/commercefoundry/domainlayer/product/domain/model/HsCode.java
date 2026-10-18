package com.commercefoundry.domainlayer.product.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 통관 신고용 HS 코드 — 숫자만 6~10자리다 (국제 공통 6자리 + 나라별 세분 최대 4자리, 한국 HSK 는 10자리).
 *
 * <p>점 · 하이픈 · 공백을 뺀 숫자열만 받는다({@code "6211.42"} 는 형식 오류). 입력을 고쳐 받는 정규화는 입력 칸(어드민 #9 · 카페24 이관 #19)이 한다 —
 * 여기서 조용히 고치면 같은 코드가 저장 경로마다 다른 문자열로 갈라진다. DB check({@code ck_sku_hs_code_format})도 같은 규칙을 막는다.
 *
 * @param value 숫자 6~10자리
 */
public record HsCode(String value) {

    public static final int MIN_LENGTH = 6;
    public static final int MAX_LENGTH = 10;

    private static final Pattern FORMAT = Pattern.compile("[0-9]{" + MIN_LENGTH + "," + MAX_LENGTH + "}");

    /**
     * @throws IllegalArgumentException 숫자 6~10자리가 아닐 때
     */
    public HsCode {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("HS 코드는 숫자 %d~%d자리여야 합니다: \"%s\"".formatted(MIN_LENGTH, MAX_LENGTH, value));
        }
    }

    /**
     * DB 에 저장된 값을 읽는다. null 은 미입력(국내 판매 전용 SKU)이다.
     *
     * @throws IllegalStateException 형식이 틀린 저장값 — 저장 데이터 오류다 (메시지에 저장값을 넣는다)
     */
    public static HsCode fromStoredOrNull(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            return new HsCode(stored);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("저장된 HS 코드 형식이 아닙니다: \"%s\"".formatted(stored), exception);
        }
    }
}
