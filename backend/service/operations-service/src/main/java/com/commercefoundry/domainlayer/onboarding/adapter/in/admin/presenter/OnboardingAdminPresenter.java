package com.commercefoundry.domainlayer.onboarding.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.item.OnboardingTourItem;
import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.response.OnboardingAdminResponse;
import com.commercefoundry.domainlayer.onboarding.domain.model.TourProgress;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OnboardingAdminPresenter {

    /** 업무 완료(tasks)는 아직 없다 — 담당 서비스 사실 조회가 붙을 때 채운다. */
    public OnboardingAdminResponse toResponse(List<TourProgress> tours) {
        return new OnboardingAdminResponse(tours.stream().map(this::toItem).toList(), List.of());
    }

    public OnboardingTourItem toItem(TourProgress progress) {
        return OnboardingTourItem.builder()
            .guideKey(progress.guideKey())
            .guideVersion(progress.guideVersion())
            .status(progress.status().toMetadata())
            .currentStep(progress.currentStep())
            .finishedAt(progress.finishedAt())
            .build();
    }
}
