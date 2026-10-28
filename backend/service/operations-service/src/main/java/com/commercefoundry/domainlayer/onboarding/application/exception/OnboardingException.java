package com.commercefoundry.domainlayer.onboarding.application.exception;

import lombok.Getter;

@Getter
public class OnboardingException extends RuntimeException {

    private final OnboardingErrorCode errorCode;

    public OnboardingException(OnboardingErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. */
    public OnboardingException(OnboardingErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public OnboardingException(OnboardingErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
