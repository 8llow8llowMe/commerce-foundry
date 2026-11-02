package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response.MarketingConsentInternalResponse;
import java.util.List;

public interface MarketingConsentInternalUseCase {

    MarketingConsentInternalResponse query(List<Long> memberIds);
}
