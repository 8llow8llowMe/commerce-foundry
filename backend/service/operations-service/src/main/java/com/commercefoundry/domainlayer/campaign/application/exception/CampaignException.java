package com.commercefoundry.domainlayer.campaign.application.exception;

import lombok.Getter;

@Getter
public class CampaignException extends RuntimeException {
    private final CampaignErrorCode errorCode;

    public CampaignException(CampaignErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
