package com.commercefoundry.domainlayer.order.application.command;

import java.util.List;
import java.util.Objects;

/**
 * 주문서 금액 계산 — 주문 항목과 배송 국가 · 우편번호. 주문서 미리보기가 그대로 쓰고, 주문 생성은 자기 요청(배송지 전체)에서 이 값을 만들어
 * 같은 계산({@code OrderQuoteProcessor#quote})을 부른다.
 *
 * <p>형식 검증(줄 수 1~50 · 수량 · 국가 코드 · 우편번호 정규식)은 요청 DTO 가 한다 — 배송지 규칙은 회원 주소록(#7)과 같다: 국가 {@code ^[A-Z]{2}$},
 * KR 우편번호 {@code ^[0-9]{5}$}(필수), 해외 우편번호 {@code ^[A-Za-z0-9 -]{1,20}$}(선택). 이 규칙이 delivery 의 견적 본문 규칙보다 느슨하면 고객 입력 오류가
 * 503 으로 보인다 ({@code ShippingFeeQuoteQuery}). 여기서는 null · 빈 목록만 막는다.
 *
 * <p>{@link #toString()} 은 우편번호를 가린다 — 배송지의 일부라 로그에 남기지 않는다 ({@code coding-conventions.md} §8).
 *
 * @param items 요청 순서. 하나 이상
 * @param countryCode 배송 국가 (ISO 3166-1 alpha-2 대문자)
 * @param postalCode 배송지 우편번호. KR 이면 필수, 그 밖의 국가는 선택(null)
 */
public record OrderQuoteCommand(List<OrderItemCommand> items, String countryCode, String postalCode) {

    public OrderQuoteCommand {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("주문 항목이 하나 이상 있어야 합니다");
        }
        Objects.requireNonNull(countryCode, "countryCode");
    }

    @Override
    public String toString() {
        return "OrderQuoteCommand[items=%s, countryCode=%s, postalCode=%s]".formatted(items, countryCode, postalCode == null ? null : "***");
    }
}
