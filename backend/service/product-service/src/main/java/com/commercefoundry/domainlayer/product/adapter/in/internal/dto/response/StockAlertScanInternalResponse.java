package com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.StockAlertInternalItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "현재 페이지의 품절 임박 관측 결과. 전체 스캔의 단일 스냅숏은 아니다")
public record StockAlertScanInternalResponse(
    @Schema(description = "페이지 조회 시작 시각") Instant observedAt,
    @Schema(description = "SKU 아이디 오름차순 결과") List<StockAlertInternalItem> contents,
    @Schema(description = "현재 관측에서 다음 페이지가 있는지 여부") boolean hasNext,
    @Schema(description = "다음 페이지 커서. hasNext=false이면 null") String nextAfterSkuId
) {
}
