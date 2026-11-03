package com.commercefoundry.domainlayer.product.application.command;

import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
import java.math.BigDecimal;
import java.util.List;

/**
 * 맞춤 제작 설정 전체 (#9 PR-B) — 등록 본문의 {@code customOrder} 와 {@code PUT /custom-order} 가 같은 모양이다.
 * 문구는 앞뒤 공백을 뺐고 빈 문구는 null 이다. 구성 규칙(키 중복 · 범위 · 증분)은 {@code ProductCustomOrderProcessor#validate} 가 본다.
 *
 * @param enabled 맞춤 제작 상품인가. 꺼도 항목 정의는 남겨 둔다 — 다시 켤 때 그대로 쓴다(주문은 켜진 상품의 정의만 받는다)
 * @param fields 배열 순서가 노출 순서다
 */
public record ProductCustomOrderCommand(boolean enabled, String notice, Integer leadTimeDays, List<Field> fields) {

    public ProductCustomOrderCommand {
        fields = List.copyOf(fields);
    }

    /** 맞춤 제작 꺼짐 · 항목 없음 — 등록 본문에 {@code customOrder} 가 없을 때. */
    public static ProductCustomOrderCommand disabled() {
        return new ProductCustomOrderCommand(false, null, null, List.of());
    }

    /**
     * @param fieldKey 주문 입력 · 장바구니가 이 키로 값을 가리킨다 — 한번 저장한 키는 바꾸지 않고, 같은 키는 같은 항목으로 본다
     * @param maxValue NUMBER 면 최대값, TEXT 면 최대 글자 수
     */
    public record Field(
        String fieldKey, String label, CustomOrderInputType inputType, String unit, boolean required,
        BigDecimal minValue, BigDecimal maxValue, BigDecimal stepValue, String helpText
    ) {
    }
}
