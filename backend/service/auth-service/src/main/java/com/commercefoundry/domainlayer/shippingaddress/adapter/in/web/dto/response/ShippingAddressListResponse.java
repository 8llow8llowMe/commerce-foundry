package com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response;

import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.item.ShippingAddressItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "내 배송지 목록 — 기본 배송지가 먼저, 그다음 최신순. 페이지 없이 전체다")
public record ShippingAddressListResponse(

    @Schema(description = "배송지. 없으면 빈 배열")
    List<ShippingAddressItem> addresses,

    @Schema(description = "회원당 저장할 수 있는 배송지 수 — 이만큼 있으면 추가가 409(AUTH_021)다", example = "20")
    int maxCount
) {
}
