package com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/** 내 안내 투어 하나의 진행 (#357). 안내를 본 기록일 뿐 실제 업무 완료가 아니다. */
@Builder
@Schema(description = "안내 투어 진행")
public record OnboardingTourItem(

    @Schema(description = "안내 키", example = "console")
    String guideKey,

    @Schema(description = "지금 배포된 안내 버전. 기록 버전이 이와 다르면 미시작으로 보인다", example = "1")
    int guideVersion,

    @Schema(description = "안내 상태 (NOT_STARTED / IN_PROGRESS / COMPLETED / SKIPPED)")
    CodeNameDescriptionMetadata status,

    @Schema(description = "마지막으로 본 단계 (0부터). 미시작이거나 모르면 null", example = "2", nullable = true)
    Integer currentStep,

    @Schema(description = "완료 · 건너뜀이 된 시각 (UTC). 끝나지 않았으면 null", example = "2026-10-28T01:00:00Z", nullable = true)
    Instant finishedAt
) {
}
