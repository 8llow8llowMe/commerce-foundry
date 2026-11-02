package com.commercefoundry.domainlayer.member.application.queryresult;

import java.util.Set;

public record MarketingConsentQueryResult(Set<Long> agreedMemberIds) {

    public MarketingConsentQueryResult {
        agreedMemberIds = Set.copyOf(agreedMemberIds);
    }
}
