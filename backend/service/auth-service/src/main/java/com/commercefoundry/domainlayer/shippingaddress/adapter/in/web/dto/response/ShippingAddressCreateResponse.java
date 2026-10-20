package com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "배송지 추가 응답")
public record ShippingAddressCreateResponse(

    @Schema(description = "배송지 아이디", example = "4611686018427387904")
    String addressId
) {
}
