package com.commercefoundry.domainlayer.order.application.command;

import java.util.List;
import java.util.Objects;

/**
 * 주문서 금액 계산 — 주문 항목과 배송 국가 · 우편번호. 주문서 미리보기가 그대로 쓰고, 주문 생성은 자기 요청(배송지 전체)에서 이 값을 만들어
 * 같은 계산({@code OrderQuoteProcessor#quote})을 부른다.
 *
 * <p>형식 검증(줄 수 1~{@value #MAX_LINES} · 수량 · 국가 코드 · 우편번호 정규식)은 요청 DTO 가 한다 — 배송지 규칙은 회원 주소록(#7)과 같다: 국가 {@code ^[A-Z]{2}$},
 * KR 우편번호 {@code ^[0-9]{5}$}(필수), 해외 우편번호 {@code ^[A-Za-z0-9 -]{1,20}$}(선택). 이 규칙이 delivery 의 견적 본문 규칙보다 느슨하면 고객 입력 오류가
 * 503 으로 보인다 ({@code ShippingFeeQuoteQuery}). 여기서는 null · 빈 목록 · 줄 수 상한만 막는다.
 *
 * <p>줄 수 상한은 <b>마지막 방어선</b>이다 — 요청 DTO 가 먼저 400 으로 거르는 것이 정상 경로이고, 여기서 걸리면 검증 누락 · 조립 버그라
 * {@link IllegalArgumentException} 이다. 재고 선점 계약(항목 ≤100 · 항목당 수량 ≤10,000)과의 관계 — 줄 {@value #MAX_LINES}개 × 줄당 수량 99
 * ({@code OrderQuoteLine#MAX_QUANTITY})라 같은 SKU 를 합쳐도 4,950 으로 선점 항목 하나의 수량 상한 안이고, SKU 수도 항목 상한 안이다.
 *
 * <p>{@link #toString()} 은 우편번호를 가린다 — 배송지의 일부라 로그에 남기지 않는다 ({@code coding-conventions.md} §8).
 *
 * @param items 요청 순서. 1~{@value #MAX_LINES}개
 * @param countryCode 배송 국가 (ISO 3166-1 alpha-2 대문자)
 * @param postalCode 배송지 우편번호. KR 이면 필수, 그 밖의 국가는 선택(null)
 */
public record OrderQuoteCommand(List<OrderItemCommand> items, String countryCode, String postalCode) {

    /** 한 주문의 줄 수 상한 — 요청 검증(1~50) · 장바구니 줄 상한(50)과 같다. */
    public static final int MAX_LINES = 50;

    public OrderQuoteCommand {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("주문 항목이 하나 이상 있어야 합니다");
        }
        if (items.size() > MAX_LINES) {
            throw new IllegalArgumentException("주문 항목은 %d개 이하입니다: %d".formatted(MAX_LINES, items.size()));
        }
        Objects.requireNonNull(countryCode, "countryCode");
    }

    @Override
    public String toString() {
        return "OrderQuoteCommand[items=%s, countryCode=%s, postalCode=%s]".formatted(items, countryCode, postalCode == null ? null : "***");
    }
}
