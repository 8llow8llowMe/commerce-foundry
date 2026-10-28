package com.commercefoundry.domainlayer.onboarding.application.port.in;

import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.item.OnboardingTourItem;
import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.response.OnboardingAdminResponse;
import com.commercefoundry.domainlayer.onboarding.application.command.TourProgressUpdateCommand;

/**
 * 운영자 온보딩 — 내 투어 진행 조회 · 기록 (#357). 운영자는 언제나 access 토큰의 memberId 다.
 */
public interface OnboardingAdminUseCase {

    OnboardingAdminResponse getMine(long memberId);

    OnboardingTourItem updateTour(long memberId, TourProgressUpdateCommand command);
}
