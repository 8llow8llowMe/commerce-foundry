package com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.onboarding.application.command.TourProgressUpdateCommand;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingValidationMessage;
import com.commercefoundry.domainlayer.onboarding.domain.model.TourStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "안내 투어 진행 기록. 운영자는 토큰에서 정한다 — 본문으로 받지 않는다. 안내 열람 · 건너뜀만 기록하고 실제 업무 완료는 선언할 수 없다")
public record OnboardingTourUpdateRequest(

    @Schema(description = "화면이 가진 안내 버전 (1 이상, 배포된 버전과 같아야 한다 — 크면 400, 작으면 409). 재실행(NOT_STARTED)은 버전을 보지 않는다", example = "1")
    @NotNull(message = OnboardingValidationMessage.GUIDE_VERSION_REQUIRED)
    @Min(value = 1, message = OnboardingValidationMessage.GUIDE_VERSION_REQUIRED)
    Integer guideVersion,

    @Schema(description = "안내 상태. NOT_STARTED 는 재실행 — 기록을 지운다", example = "IN_PROGRESS",
        allowableValues = {"NOT_STARTED", "IN_PROGRESS", "COMPLETED", "SKIPPED"})
    @NotNull(message = OnboardingValidationMessage.STATUS_REQUIRED)
    TourStatus status,

    @Schema(description = "마지막으로 본 단계 (0 ~ 200). 없으면 null", example = "2", nullable = true)
    @Min(value = 0, message = OnboardingValidationMessage.CURRENT_STEP_RANGE)
    @Max(value = 200, message = OnboardingValidationMessage.CURRENT_STEP_RANGE)
    Integer currentStep
) {

    public TourProgressUpdateCommand toCommand(String guideKey) {
        return new TourProgressUpdateCommand(guideKey, guideVersion, status, currentStep);
    }
}
