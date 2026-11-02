package com.commercefoundry.domainlayer.campaign.application.exception;

public final class CampaignValidationMessage {
    public static final String MEMBERS_REQUIRED = "CAMPAIGN_101:memberIds 는 한 개 이상 필요합니다.";
    public static final String MEMBERS_LIMIT = "CAMPAIGN_102:memberIds 는 중복을 포함해 최대 100개입니다.";
    public static final String MEMBER_POSITIVE = "CAMPAIGN_103:memberId 는 null 이 아닌 양수여야 합니다.";

    private CampaignValidationMessage() {
    }
}
