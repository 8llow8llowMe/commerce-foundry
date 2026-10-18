package com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "해외 배송 국가 그룹")
public record ShippingZoneAdminItem(

    @Schema(description = "국가 그룹 아이디. 정책을 교체(PUT)할 때마다 새로 발급되므로 저장해 두고 다시 쓰지 않는다", example = "4611686018427387906")
    String shippingZoneId,

    @Schema(description = "국가 그룹 이름", example = "아시아")
    String name,

    @Schema(description = "국가 코드 (ISO 3166-1 alpha-2). 오름차순", example = "[\"JP\", \"SG\"]")
    List<String> countryCodes,

    @Schema(description = "무게 구간. 상한 무게 오름차순")
    List<ShippingZoneRateAdminItem> rates
) {
}
