package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "클레임 접수 항목")
public record ClaimAdminLineItem(
    @Schema(description = "주문 항목 아이디", example = "4611686018427387905")
    String orderItemId,

    @Schema(description = "접수 수량", example = "1")
    int quantity,

    @Schema(description = "접수 사유")
    CodeNameDescriptionMetadata reason
) {
}
