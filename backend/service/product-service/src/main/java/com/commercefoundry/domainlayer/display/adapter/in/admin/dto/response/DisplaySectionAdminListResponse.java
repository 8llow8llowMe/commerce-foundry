package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/**
 * 페이지 없이 전체를 낸다 — 섹션 수 상한이 50 이고, 어드민이 순서 변경 화면에서 전체를 한 번에 끌어 놓아야 한다(어드민 카테고리 트리와 같은 이유, {@code api-design-guide.md} §2-5).
 */
@Builder
@Schema(description = "어드민 전시 섹션 전체 (최대 50개)")
public record DisplaySectionAdminListResponse(

    @Schema(description = "섹션 전체 — 노출 순서 → 아이디 오름차순. 숨김 · 기간 밖 섹션도 담는다")
    List<DisplaySectionAdminResponse> sections
) {
}
