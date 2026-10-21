package com.commercefoundry.domainlayer.order.domain.model;

import java.text.Normalizer;
import java.util.Objects;

/**
 * 주문 줄의 맞춤 제작 입력 한 칸 (가슴둘레 · 요청 사항 등). 장바구니의 같은 값을 import 하지 않고 같은 규칙을 order 에 둔다 —
 * 컨텍스트 간 허용 목록(Processor · 그 domain model) 밖이다 ({@code architecture-guide.md} §3). 규칙 정본은 {@code order-flow-guide.md} §2-1 "줄 동일성" 이다.
 *
 * <p>키 · 값은 앞뒤 공백을 자른 뒤 Unicode NFC 로 정규화한다 — 줄 해시와 (주문 생성의) 스냅숏이 모두 이 값이다. NFD 로 온 한글도 같은 입력이다.
 *
 * <p>값은 HTML 을 거르지 않은 원문이다 — 주문 항목 스냅숏 · 어드민 주문 상세는 텍스트로만 렌더링한다(저장형 XSS, #12).
 * 입력이 상품의 입력 항목 정의에 맞는지는 #97 이 본다.
 *
 * <p>{@link #toString()} 은 값을 가린다 — 치수 같은 개인 정보가 들어 있어 로그에 남기지 않는다 ({@code coding-conventions.md} §8).
 *
 * @param fieldKey 상품 입력 항목 키 (chest, memo)
 * @param value 입력값 (정규화한 값)
 */
public record OrderCustomOption(String fieldKey, String value) {

    public OrderCustomOption {
        Objects.requireNonNull(fieldKey, "fieldKey");
        Objects.requireNonNull(value, "value");
        fieldKey = normalize(fieldKey);
        value = normalize(value);
    }

    @Override
    public String toString() {
        return "OrderCustomOption[fieldKey=%s, value=***]".formatted(fieldKey);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text.strip(), Normalizer.Form.NFC);
    }
}
