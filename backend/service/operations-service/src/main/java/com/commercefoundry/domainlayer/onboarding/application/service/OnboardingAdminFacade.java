package com.commercefoundry.domainlayer.onboarding.application.service;

import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.item.OnboardingTourItem;
import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.response.OnboardingAdminResponse;
import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.presenter.OnboardingAdminPresenter;
import com.commercefoundry.domainlayer.onboarding.application.command.TourProgressUpdateCommand;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingErrorCode;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingException;
import com.commercefoundry.domainlayer.onboarding.application.port.in.OnboardingAdminUseCase;
import com.commercefoundry.domainlayer.onboarding.application.service.processor.OnboardingTourProcessor;
import com.commercefoundry.domainlayer.onboarding.domain.model.TourProgress;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 운영자 온보딩 어드민 API (#357).
 *
 * <p>트랜잭션을 이 Facade 에 걸지 않는다({@code architecture-guide.md} §3 Facade 예외). ① 다른 기기 · 탭과 같은 기록을 다퉈 버전 · 첫 행 유니크 키에서 지면
 * 새 트랜잭션에서 다시 읽어야 이긴 쪽 기록과 합쳐진다 — 트랜잭션은 시도마다 {@link OnboardingTourProcessor} 가 연다.
 * ② 업무 사실 조회(PR-B)가 원격 호출이라 같은 Facade 에 섞여도 트랜잭션이 원격 호출을 붙잡지 않게.
 */
@Service
@RequiredArgsConstructor
public class OnboardingAdminFacade implements OnboardingAdminUseCase {

    /** 낙관 충돌 · 첫 행 경합으로 졌을 때 다시 읽어 합치는 횟수(첫 시도 포함). 넘으면 {@code ONBOARDING_021}. */
    static final int UPDATE_ATTEMPTS = 3;

    private final OnboardingTourProcessor onboardingTourProcessor;
    private final OnboardingAdminPresenter onboardingAdminPresenter;

    @Override
    public OnboardingAdminResponse getMine(long memberId) {
        return onboardingAdminPresenter.toResponse(onboardingTourProcessor.getMine(memberId));
    }

    @Override
    public OnboardingTourItem updateTour(long memberId, TourProgressUpdateCommand command) {
        return onboardingAdminPresenter.toItem(update(memberId, command));
    }

    private TourProgress update(long memberId, TourProgressUpdateCommand command) {
        for (int attempt = 1; ; attempt++) {
            try {
                return onboardingTourProcessor.update(memberId, command);
            } catch (OnboardingException exception) {
                if (exception.getErrorCode() != OnboardingErrorCode.CONCURRENT_UPDATE || attempt >= UPDATE_ATTEMPTS) {
                    throw exception;
                }
            }
        }
    }
}
