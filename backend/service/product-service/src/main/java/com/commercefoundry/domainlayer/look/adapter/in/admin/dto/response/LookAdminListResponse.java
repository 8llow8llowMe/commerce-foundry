package com.commercefoundry.domainlayer.look.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/**
 * 페이지 없이 전체를 낸다 — 룩 수 상한이 100 이고, 어드민이 순서 변경 화면에서 전체를 한 번에 끌어 놓아야 한다(메인 전시와 같은 이유).
 */
@Builder
@Schema(description = "어드민 룩 전체 (최대 100개)")
public record LookAdminListResponse(

    @Schema(description = "룩 전체 — 배열 순서 = 노출 순서. 숨긴 룩도 담는다")
    List<LookAdminResponse> looks
) {
}
