package com.commercefoundry.domainlayer.shipment.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 택배사 ({@code order-flow-guide.md} §4 — 택배사 코드는 enum 으로 관리한다). 목록에 없는 택배사는 {@link #OTHER} 로 등록한다.
 * 택배사 추적({@code ShipmentTrackingPort}, 후속 이슈)이 생기면 추적 서비스의 택배사 코드와의 대응은 그 어댑터가 갖는다.
 *
 * <p>값을 더하거나 빼면 {@code delivery.shipment} 의 carrier check 제약도 바꾼다 ({@code coding-conventions.md} §9-8).
 */
@Getter
@RequiredArgsConstructor
public enum CarrierCode implements CodeNameDescribable {

    CJ_LOGISTICS("CJ대한통운"),
    KOREA_POST("우체국택배"),
    LOTTE("롯데택배"),
    HANJIN("한진택배"),
    LOGEN("로젠택배"),
    EMS("EMS"),
    DHL("DHL"),
    FEDEX("FedEx"),
    UPS("UPS"),
    OTHER("기타 택배사");

    private final String displayName;
}
