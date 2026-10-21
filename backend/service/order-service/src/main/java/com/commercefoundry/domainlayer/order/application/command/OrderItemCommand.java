package com.commercefoundry.domainlayer.order.application.command;

import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOptions;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import java.util.Objects;

/**
 * 주문할 한 줄 — 주문서 미리보기와 주문 생성이 같이 쓴다. 가격 · 상품명은 받지 않는다(서버가 SKU 를 조회해 정한다).
 *
 * <p>수량 범위는 <b>마지막 방어선</b>이다 — 요청 DTO 가 먼저 400 으로 거르는 것이 정상 경로이고, 여기서 걸리면 검증 누락 · 조립 버그라
 * {@link IllegalArgumentException} 이다. 상품의 1회 주문 최대 수량(상품 단위)은 {@code OrderQuoteProcessor} 가 업무 거절로 본다.
 *
 * @param quantity 1 이상 {@value OrderQuoteLine#MAX_QUANTITY} 이하 (요청 검증이 먼저 거른다)
 * @param customOptions 맞춤 제작 입력 (정규화한 값). 없으면 빈 묶음
 */
public record OrderItemCommand(long skuId, int quantity, OrderCustomOptions customOptions) {

    public OrderItemCommand {
        if (quantity < 1 || quantity > OrderQuoteLine.MAX_QUANTITY) {
            throw new IllegalArgumentException("줄 수량은 1 이상 %d 이하입니다: %d".formatted(OrderQuoteLine.MAX_QUANTITY, quantity));
        }
        Objects.requireNonNull(customOptions, "customOptions");
    }
}
