package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderQuoteResponse;
import com.commercefoundry.domainlayer.order.application.command.OrderQuoteCommand;

/**
 * 고객 주문서 금액 미리보기 — 저장하지 않는 계산이다. 주문 생성({@code OrderWebUseCase})과 같은 계산 코드를 쓴다.
 */
public interface OrderQuoteWebUseCase {

    /**
     * 거절: 판정 순서대로 처음 걸린 하나 — {@code DUPLICATE_LINE}(400) · {@code ITEM_NOT_PURCHASABLE}(409) · {@code CUSTOM_OPTION_NOT_ALLOWED}(400) ·
     * {@code QUANTITY_EXCEEDED}(409) · {@code CURRENCY_MIXED}(409) · 해외 {@code INTERNATIONAL_SHIPPING_INFO_MISSING}(409) · 배송비 견적 거절(011~015) ·
     * 상품 조회 장애 503 ({@code OrderQuoteProcessor#quote}, {@code order-flow-guide.md} §2-3)
     */
    OrderQuoteResponse quote(OrderQuoteCommand command);
}
