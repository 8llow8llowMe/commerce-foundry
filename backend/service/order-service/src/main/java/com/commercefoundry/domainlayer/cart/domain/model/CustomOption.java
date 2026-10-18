package com.commercefoundry.domainlayer.cart.domain.model;

import java.text.Normalizer;
import java.util.Objects;

/**
 * 맞춤 제작 입력 한 칸 (가슴둘레 · 요청 사항 등). 값이 상품의 입력 항목 정의에 맞는지는 주문 생성(#13)이 본다 — 장바구니는 형식만 본다.
 *
 * <p>키 · 값은 앞뒤 공백을 자른 뒤 Unicode NFC 로 정규화한다 — 저장값과 줄 해시가 모두 이 값이다. 같은 한글이라도 입력 환경(macOS 등)에 따라
 * 자모가 나뉜 NFD 로 올 수 있는데, 정규화하지 않으면 눈에 같은 입력이 다른 줄이 된다.
 *
 * @param id 저장된 행의 아이디 (Snowflake). 아직 저장하지 않은 입력이면 null — 줄을 새로 만들 때 Processor 가 채운다
 * @param fieldKey 상품 입력 항목 키 (chest, memo)
 * @param value 입력값 (정규화한 값)
 */
public record CustomOption(Long id, String fieldKey, String value) {

    public CustomOption {
        Objects.requireNonNull(fieldKey, "fieldKey");
        Objects.requireNonNull(value, "value");
        fieldKey = normalize(fieldKey);
        value = normalize(value);
    }

    /** 아직 저장하지 않은 입력. */
    public static CustomOption of(String fieldKey, String value) {
        return new CustomOption(null, fieldKey, value);
    }

    CustomOption withId(long newId) {
        return new CustomOption(newId, fieldKey, value);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text.strip(), Normalizer.Form.NFC);
    }
}
