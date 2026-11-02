package com.commercefoundry.domainlayer.member.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.item.MarketingConsentInternalItem;
import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response.MarketingConsentInternalResponse;
import com.commercefoundry.domainlayer.member.application.info.MemberMarketingConsentInfo;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MarketingConsentInternalPresenter {

    public MarketingConsentInternalResponse toResponse(List<MemberMarketingConsentInfo> members) {
        return new MarketingConsentInternalResponse(members.stream()
            .map(member -> new MarketingConsentInternalItem(String.valueOf(member.memberId()), member.marketingAgreed())).toList());
    }
}
