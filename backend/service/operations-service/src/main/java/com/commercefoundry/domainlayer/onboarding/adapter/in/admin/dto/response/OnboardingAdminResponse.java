package com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.response;

import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.item.OnboardingTaskItem;
import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.item.OnboardingTourItem;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 내 온보딩 — 안내 투어 진행과 실제 업무 완료를 따로 둔다 (#357). */
@Schema(description = "내 온보딩")
public record OnboardingAdminResponse(

    @ArraySchema(arraySchema = @Schema(description = "안내 투어 전부 — 설정 순서. 기록이 없으면 미시작"))
    List<OnboardingTourItem> tours,

    @ArraySchema(arraySchema = @Schema(description = "실제 업무 완료 상태 전부 — 고정 순서. 담당 서비스의 사실로만 판정하고, 담당 서비스 장애면 그 업무만 UNAVAILABLE 이다"))
    List<OnboardingTaskItem> tasks
) {
}
