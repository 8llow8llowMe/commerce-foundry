package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.application.model.ShippingFeeQuoteQuery;
import com.commercefoundry.domainlayer.order.application.port.out.query.ShippingFeeQuoteQueryResult;
import com.commercefoundry.shared.commerce.Money;

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

    /**
     * 해외 배송 국가로 배송할 수 있는가 — 해외 주문에 무게 · HS 코드가 없는 SKU 가 있을 때만 부른다. 배송할 수 없는 국가라는 사유가
     * 무게 · HS 미입력보다 앞서야 하는데({@code order-flow-guide.md} §2-2), 그 판정(해외 배송 플래그 · 국가 그룹)은 delivery 에만 있다.
     * 어떻게 묻는지(어느 요청을 보내고 어느 거절을 "국가는 배송 가능" 으로 읽는지)는 구현이 정한다.
     *
     * @param orderAmount 할인 후 상품 합계(배송비 제외) — 실제 견적과 같은 값
     * @param countryCode 해외 배송 국가 (KR 이 아니다)
     * @param postalCode 배송지 우편번호. 없으면 null
     * @return 배송할 수 없는 국가(해외 배송 꺼짐 · 어느 국가 그룹에도 없음)면 false. 국가를 판정한 뒤의 사유(무게 구간)로 거절되거나 견적이 나오면 true 다
     * @throws IllegalArgumentException 국내(KR) 배송 국가 — 국내 주문은 무게 · HS 를 보지 않아 물을 일이 없다. 국가 코드 형식이 아님 — 요청 검증이 먼저 거르는 조립 버그다
     * @throws com.commercefoundry.domainlayer.order.application.exception.OrderException
     *     {@code SHIPPING_UNAVAILABLE}(409, 정책 미설정) · {@code DESTINATION_CURRENCY_NOT_SUPPORTED}(400) — delivery 가 국가를 보기 전에 거절해 국가를 판정하지 못했다.
     *     무게 · HS 를 채워도 실제 견적이 같은 이유로 실패하므로 그 코드로 답한다. {@code DELIVERY_SERVICE_UNAVAILABLE}(503) — 서킷 오픈 · 전송 실패 · 타임아웃 · 5xx ·
     *     그 밖의 4xx · 응답 계약 위반
     */
    boolean isDestinationSupported(Money orderAmount, String countryCode, String postalCode);
}
