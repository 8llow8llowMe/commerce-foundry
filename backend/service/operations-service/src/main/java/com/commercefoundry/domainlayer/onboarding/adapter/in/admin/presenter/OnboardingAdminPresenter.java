package com.commercefoundry.domainlayer.onboarding.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.item.OnboardingTaskItem;
import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.item.OnboardingTourItem;
import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.response.OnboardingAdminResponse;
import com.commercefoundry.domainlayer.onboarding.domain.model.OnboardingTask;
import com.commercefoundry.domainlayer.onboarding.domain.model.TourProgress;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OnboardingAdminPresenter {

    public OnboardingAdminResponse toResponse(List<TourProgress> tours, List<OnboardingTask> tasks) {
        return new OnboardingAdminResponse(tours.stream().map(this::toItem).toList(), tasks.stream().map(this::toTaskItem).toList());
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

    private OnboardingTaskItem toTaskItem(OnboardingTask task) {
        return OnboardingTaskItem.builder()
            .taskKey(task.key().name())
            .storeStatus(task.storeStatus().toMetadata())
            .myStatus(task.myStatus().toMetadata())
            .myCompletedAt(task.myCompletedAt())
            .build();
    }
}
