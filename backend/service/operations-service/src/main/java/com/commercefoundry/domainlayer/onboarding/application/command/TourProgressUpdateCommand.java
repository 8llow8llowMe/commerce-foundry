package com.commercefoundry.domainlayer.onboarding.application.command;

import com.commercefoundry.domainlayer.onboarding.domain.model.TourStatus;

/**
 * 투어 진행 기록 요청 (#357). 운영자는 토큰에서 받는다 — 여기에 담지 않는다.
 *
 * @param status {@code NOT_STARTED} 면 재실행 — 그 운영자의 기록을 지운다
 * @param currentStep 마지막으로 본 단계 (0부터). 없으면 null
 */
public record TourProgressUpdateCommand(String guideKey, int guideVersion, TourStatus status, Integer currentStep) {
}
