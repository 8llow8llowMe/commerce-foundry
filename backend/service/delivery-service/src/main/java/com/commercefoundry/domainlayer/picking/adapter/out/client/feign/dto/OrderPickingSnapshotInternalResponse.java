package com.commercefoundry.domainlayer.picking.adapter.out.client.feign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * order {@code GET /internal/v1/orders/picking-snapshots} 응답 한 건 — delivery 가 소유하는 수신 모양이다(order 의 DTO 를 공유하지 않는다). 필요한 칸만 받고
 * 나머지(주문 시점 상품 아이디 등)는 무시한다. 식별자는 문자열로 온다.
 *
 * @param countryCode 배송지가 없으면 null
 * @param lines       항목이 없으면 빈 배열
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPickingSnapshotInternalResponse(String orderId, String status, String countryCode, List<Line> lines) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String skuId, Long quantity, Boolean customOrder) {
    }
}
