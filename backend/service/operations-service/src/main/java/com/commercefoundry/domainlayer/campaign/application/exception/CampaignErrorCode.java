package com.commercefoundry.domainlayer.campaign.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CampaignErrorCode {
    CONSENT_SERVICE_UNAVAILABLE("CAMPAIGN_001", "수신 동의를 잠시 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    INVALID_REQUEST("CAMPAIGN_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
