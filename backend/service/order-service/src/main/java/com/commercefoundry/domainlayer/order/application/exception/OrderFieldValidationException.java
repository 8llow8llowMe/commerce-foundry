package com.commercefoundry.domainlayer.order.application.exception;

import java.util.List;

/**
 * 맞춤 제작 입력이 상품 항목 정의와 다를 때. 400 이고 {@code fieldErrors[].field} 는 {@code items[i].customOptions[j].value} 다.
 * 메시지는 입력값을 싣지 않는다 — 치수가 로그 · 응답 대표 문장 밖으로 나가지 않게.
 */
public class OrderFieldValidationException extends RuntimeException {

    private final List<Violation> violations;

    public OrderFieldValidationException(List<Violation> violations) {
        super(violations.getFirst().message());
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }

    /**
     * @param field 요청 본문 경로 {@code items[i].customOptions[j].value}. 빠진 필수 항목은 그 줄에 있던 입력 뒤의 칸 번호다
     */
    public record Violation(String code, String field, String message) {
    }
}
