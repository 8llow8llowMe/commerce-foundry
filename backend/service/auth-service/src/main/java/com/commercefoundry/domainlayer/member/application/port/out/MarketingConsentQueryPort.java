package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.domainlayer.member.application.queryresult.MarketingConsentQueryResult;
import java.util.List;

public interface MarketingConsentQueryPort {

    MarketingConsentQueryResult findLatestAgreedCustomers(List<Long> memberIds);
}
