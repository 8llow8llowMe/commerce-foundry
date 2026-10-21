package com.commercefoundry.domainlayer.order.application.command;

import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOptions;
import java.util.Objects;

/**
 * 주문할 한 줄 — 주문서 미리보기와 주문 생성이 같이 쓴다. 가격 · 상품명은 받지 않는다(서버가 SKU 를 조회해 정한다).
 *
 * @param quantity 1 이상 99 이하 (요청 검증이 거른다)
 * @param customOptions 맞춤 제작 입력 (정규화한 값). 없으면 빈 묶음
 */
public record OrderItemCommand(long skuId, int quantity, OrderCustomOptions customOptions) {

    public OrderItemCommand {
        Objects.requireNonNull(customOptions, "customOptions");
    }
}
