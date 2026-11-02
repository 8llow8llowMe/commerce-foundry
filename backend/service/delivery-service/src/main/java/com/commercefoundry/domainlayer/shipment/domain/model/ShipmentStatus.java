package com.commercefoundry.domainlayer.shipment.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 배송(송장) 상태 ({@code order-flow-guide.md} §4). 출고 대기(READY)는 두지 않는다 — 주문 상품 준비중이 곧 출고 대기이고, 송장 행은 등록할 때 생긴다.
 *
 * <p>송장 등록(#18 PR-B1)은 {@link #SHIPPED} 만 만든다. {@link #IN_TRANSIT} · {@link #DELIVERED} 로 바꾸는 흐름(배송 상태 변경 · 택배사 추적)은 PR-B2 · 후속이다.
 * 값을 더하거나 빼면 {@code delivery.shipment} · {@code delivery.shipment_status_history} 의 check 제약도 바꾼다 ({@code coding-conventions.md} §9-8).
 */
@Getter
@RequiredArgsConstructor
public enum ShipmentStatus implements CodeNameDescribable {

    SHIPPED("출고 완료", "송장이 등록되어 상품이 출고되었습니다."),
    IN_TRANSIT("배송 중", "택배사가 상품을 옮기고 있습니다."),
    DELIVERED("배송 완료", "상품이 배송되었습니다.");

    private final String displayName;
    private final String description;
}
