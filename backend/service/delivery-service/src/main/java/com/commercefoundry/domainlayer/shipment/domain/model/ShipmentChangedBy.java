package com.commercefoundry.domainlayer.shipment.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 배송 상태 이력의 변경 주체. 송장 등록 · 어드민 배송 상태 변경은 {@link #ADMIN}(아이디 = access 토큰의 memberId), 택배사 추적(후속)은 {@link #SYSTEM} 이다.
 * 값을 더하면 {@code delivery.shipment_status_history} 의 check 제약도 바꾼다.
 */
@Getter
@RequiredArgsConstructor
public enum ShipmentChangedBy implements CodeNameDescribable {

    ADMIN("관리자"),
    SYSTEM("자동 처리");

    private final String displayName;
}
