package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/** 어드민 주문 목록 한 줄 (#17). 받는 분 이름은 마스킹한 값만 내린다. */
@Builder
@Schema(description = "어드민 주문 목록 한 줄")
public record OrderAdminItem(

    @Schema(description = "주문 아이디", example = "4611686018427387904")
    String orderId,

    @Schema(description = "주문한 회원 아이디", example = "4611686018427387000")
    String memberId,

    @Schema(description = "주문 상태", example = "{\"code\":\"PAID\",\"name\":\"결제 완료\",\"description\":\"결제가 완료되었습니다.\"}")
    CodeNameDescriptionMetadata status,

    @Schema(description = "주문 이름 — 첫 상품명, 여러 상품이면 \"첫 상품명 외 N건\"", example = "모시 저고리 외 1건")
    String orderName,

    @Schema(description = "결제 금액 (최소 화폐 단위 — KRW 원, USD 센트)", example = "93000")
    long totalAmount,

    @Schema(description = "통화 (ISO 4217)", example = "KRW")
    String currency,

    @Schema(description = "받는 분 (마스킹)", example = "홍*동")
    String recipientName,

    @Schema(description = "주문 시각 (UTC, ISO-8601)", example = "2026-10-18T09:00:00Z")
    Instant createdAt,

    @Schema(description = "결제 기한 (UTC, ISO-8601)", example = "2026-10-18T09:30:00Z")
    Instant paymentDueAt
) {
}
