package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 고객 주문 취소 사유 (#16). 코드는 주문 상태 이력의 사유와 {@code order-canceled} 이벤트의 {@code reason} 에 그대로 실린다 ({@code order-flow-guide.md} §3-5) —
 * 코드를 바꾸거나 지우지 않는다(더하기만 한다). 자유 입력은 받지 않는다(개인정보가 이벤트 · 이력으로 새지 않게).
 */
@Getter
@RequiredArgsConstructor
public enum OrderCancelReason implements CodeNameDescribable {

    CHANGE_OF_MIND("단순 변심", "상품이 더 이상 필요하지 않습니다."),
    ORDER_MISTAKE("주문 내용 잘못 선택", "상품 · 옵션 · 수량 · 배송지를 잘못 골랐습니다."),
    PAYMENT_ISSUE("결제 문제", "결제 수단을 바꾸려 하거나 결제가 되지 않습니다."),
    OTHER("기타", "그 밖의 사유입니다.");

    private final String displayName;
    private final String description;
}
