package com.commercefoundry.domainlayer.order.adapter.in.web.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "반품 접수 결과. 신규 201, 같은 키·본문 재생 200")
public record OrderReturnResponse(
    @Schema(description = "클레임 아이디", example = "4611686018427387906")
    String claimId,

    @Schema(description = "주문 아이디", example = "4611686018427387904")
    String orderId,

    @Schema(description = "접수 상태 (REQUESTED)")
    CodeNameDescriptionMetadata status,

    @Schema(description = "접수 항목 (주문 항목 아이디 오름차순)")
    List<Item> items,

    @Schema(description = "예상 환불 금액. 미산정이므로 null이며 0원을 뜻하지 않는다", nullable = true)
    MoneyItem expectedRefund,

    @Schema(description = "접수 시각 (UTC). 같은 키 재생에서도 동일하다")
    Instant requestedAt
) {
    @Schema(name = "OrderReturnResponseItem", description = "접수된 반품 항목")
    public record Item(
        @Schema(description = "주문 항목 아이디", example = "4611686018427387905")
        String orderItemId,

        @Schema(description = "접수 수량", example = "1")
        int quantity,

        @Schema(description = "접수 사유 코드·이름·설명")
        CodeNameDescriptionMetadata reason
    ) {
    }
}
