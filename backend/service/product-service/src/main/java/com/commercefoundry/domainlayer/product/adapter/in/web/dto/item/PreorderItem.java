package com.commercefoundry.domainlayer.product.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import lombok.Builder;

@Builder
@Schema(description = "프리오더(예약 판매) 안내. 판매 · 재고 판정은 일반 상품과 같고, 화면에 발송 예정 안내만 더한다")
public record PreorderItem(

    @Schema(
        description = "발송 예정일 (yyyy-MM-dd, 순차 발송 시작일 — 스토어 현지 날짜). null 이면 '발송일 추후 안내' 로 표시한다",
        type = "string", format = "date", example = "2026-11-20", nullable = true
    )
    LocalDate expectedShipDate
) {
}
