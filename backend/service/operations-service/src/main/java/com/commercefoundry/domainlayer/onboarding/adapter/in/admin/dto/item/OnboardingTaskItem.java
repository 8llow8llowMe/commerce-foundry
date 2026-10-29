package com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/** 실제 업무 하나의 완료 상태 (#357). 담당 서비스의 사실로만 판정한다 — 안내 투어를 본 기록과 관계없다. */
@Builder
@Schema(description = "실제 업무 완료 상태")
public record OnboardingTaskItem(

    @Schema(description = "업무 키 — 화면 분기용 제어 코드", example = "SHIPPING_POLICY_SAVED", allowableValues = {"SHIPPING_POLICY_SAVED"})
    String taskKey,

    @Schema(description = "스토어 공통 준비 상태 (READY / NOT_READY / UNAVAILABLE) — 누가 했든 스토어에 설정이 있는가. UNAVAILABLE 은 담당 서비스 장애로 확인하지 못함")
    CodeNameDescriptionMetadata storeStatus,

    @Schema(description = "본인 실습 상태 (COMPLETED / NOT_COMPLETED / NO_EVIDENCE / UNAVAILABLE). "
        + "NO_EVIDENCE 는 스토어엔 설정이 있지만 본인이 한 기록이 없음 — 완료가 아니다")
    CodeNameDescriptionMetadata myStatus,

    @Schema(description = "본인이 처음 해낸 시각 (UTC). myStatus 가 COMPLETED 일 때만 있다", example = "2026-10-28T01:00:00Z", nullable = true)
    Instant myCompletedAt
) {
}
