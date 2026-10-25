package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.item.DisplayElementAdminItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "어드민 전시 섹션 (숨김 · 기간 밖 포함)")
public record DisplaySectionAdminResponse(

    @Schema(description = "섹션 아이디", example = "4611686018427387904")
    String sectionId,

    @Schema(
        description = "섹션 유형 — HERO · PRODUCT_PICK · STORY · LOOK · DETAIL",
        example = "{\"code\": \"PRODUCT_PICK\", \"name\": \"상품 픽\", \"description\": \"운영자가 고른 상품 묶음입니다.\"}"
    )
    CodeNameDescriptionMetadata sectionType,

    @Schema(description = "제목. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", example = "가을 생활한복", nullable = true)
    String title,

    @Schema(description = "부제목. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", nullable = true)
    String subtitle,

    @Schema(description = "본문. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", nullable = true)
    String body,

    @Schema(description = "이동 주소. 없으면 null", example = "/products?categoryId=11", nullable = true)
    String linkUrl,

    @Schema(description = "이동 버튼 문구. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", example = "더 보기", nullable = true)
    String linkLabel,

    @Schema(description = "노출 여부 설정", example = "true")
    boolean visible,

    @Schema(description = "노출 시작 시각(포함, UTC). 없으면 null", example = "2026-11-01T00:00:00Z", nullable = true)
    Instant startAt,

    @Schema(description = "노출 종료 시각(제외, UTC). 없으면 null", example = "2026-12-01T00:00:00Z", nullable = true)
    Instant endAt,

    @Schema(
        description = "지금 노출 상태 — LIVE(노출 중) · HIDDEN(숨김, visible=false) · SCHEDULED(예정, startAt 전) · ENDED(종료, endAt 이후). "
            + "숨김이 기간보다 앞서고, 시작 시각은 포함 · 종료 시각은 제외다. 서버 시각 기준 파생 값이며 고객 메인 노출과 같은 판정이다",
        example = "{\"code\": \"SCHEDULED\", \"name\": \"예정\", \"description\": \"노출 시작 전이라 아직 고객 메인에 보이지 않습니다.\"}"
    )
    CodeNameDescriptionMetadata liveStatus,

    @Schema(description = "아이템 전체 (섹션 안의 순서). 숨긴 상품의 아이템도 담는다")
    List<DisplayElementAdminItem> items
) {
}
