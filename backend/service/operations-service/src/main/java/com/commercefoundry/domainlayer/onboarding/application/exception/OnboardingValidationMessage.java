package com.commercefoundry.domainlayer.onboarding.application.exception;

/**
 * 운영자 온보딩 API 요청 검증 메시지 — {@code "<코드>:<문구>"} ({@code coding-conventions.md} §8). 대역은 {@code ONBOARDING_1xx} 다.
 * {@code 103}(배포되지 않은 버전)은 설정과 비교해야 해 Processor 가 {@link OnboardingErrorCode#UNRELEASED_GUIDE_VERSION} 으로 던진다.
 */
public final class OnboardingValidationMessage {

    /** 안내 키 형식 — yml 허용 목록의 키 형식과 같다. */
    public static final String GUIDE_KEY_PATTERN_REGEXP = "^[a-z][a-z0-9-]{0,63}$";

    public static final String GUIDE_KEY_PATTERN = "ONBOARDING_101:guideKey 는 영문 소문자로 시작하는 소문자 · 숫자 · 하이픈 1 ~ 64자여야 합니다.";
    public static final String GUIDE_VERSION_REQUIRED = "ONBOARDING_102:guideVersion 은 필수이고 1 이상이어야 합니다.";
    public static final String STATUS_REQUIRED = "ONBOARDING_104:status 는 필수입니다.";
    public static final String CURRENT_STEP_RANGE = "ONBOARDING_105:currentStep 은 0 이상 200 이하여야 합니다.";

    private OnboardingValidationMessage() {
    }
}
