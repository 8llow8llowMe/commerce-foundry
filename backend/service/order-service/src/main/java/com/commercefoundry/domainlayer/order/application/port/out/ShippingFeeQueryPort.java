package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.application.model.ShippingFeeQuoteQuery;
import com.commercefoundry.domainlayer.order.application.port.out.query.ShippingFeeQuoteQueryResult;

/**
 * delivery-service 의 배송비 견적. 정책으로 계산만 하고 아무것도 저장하지 않는 서비스 간 읽기라 {@code *QueryPort} 다 ({@code coding-conventions.md} §5-1).
 *
 * <p>주문서 미리보기와 주문 생성이 같은 견적을 쓴다. 주문 생성은 재고 선점보다 먼저 부른다 — 견적이 거절되면 선점 없이 끝난다 (#13 · #70).
 */
public interface ShippingFeeQueryPort {

    /**
     * @return 견적. 금액은 모두 주문 금액({@code query.orderAmount()})과 같은 통화다
     * @throws com.commercefoundry.domainlayer.order.application.exception.OrderException
     *     {@code SHIPPING_UNAVAILABLE}(409, 정책 미설정) · {@code DESTINATION_NOT_SUPPORTED}(400) · {@code DESTINATION_CURRENCY_NOT_SUPPORTED}(400) ·
     *     {@code SHIPPING_WEIGHT_NOT_SUPPORTED}(400) — delivery 의 업무 거절. {@code DELIVERY_SERVICE_UNAVAILABLE}(503) — 서킷 오픈 · 전송 실패 · 타임아웃 · 5xx ·
     *     그 밖의 4xx · 응답 계약 위반
     */
    ShippingFeeQuoteQueryResult quote(ShippingFeeQuoteQuery query);
}
