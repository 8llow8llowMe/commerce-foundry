package com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "해외 배송 국가 그룹 — 같은 무게 요금표를 쓰는 국가들")
public record ShippingZoneItem(

    @Schema(description = "국가 그룹 이름", example = "아시아")
    String name,

    @Schema(description = "국가 코드 (ISO 3166-1 alpha-2). 오름차순", example = "[\"JP\", \"SG\"]")
    List<String> countryCodes,

    @Schema(description = "무게 구간. 상한 무게 오름차순 — 가장 큰 구간의 상한보다 무거우면 배송할 수 없다")
    List<ShippingZoneRateItem> rates
) {
}
