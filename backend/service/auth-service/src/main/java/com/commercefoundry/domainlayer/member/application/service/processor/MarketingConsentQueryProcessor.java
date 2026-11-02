package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.info.MemberMarketingConsentInfo;
import com.commercefoundry.domainlayer.member.application.port.out.MarketingConsentQueryPort;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MarketingConsentQueryProcessor {

    private final MarketingConsentQueryPort marketingConsentQueryPort;

    public List<MemberMarketingConsentInfo> query(List<Long> memberIds) {
        List<Long> distinctIds = memberIds.stream().distinct().toList();
        Set<Long> agreed = marketingConsentQueryPort.findLatestAgreedCustomers(distinctIds).agreedMemberIds();
        return distinctIds.stream().map(id -> new MemberMarketingConsentInfo(id, agreed.contains(id))).toList();
    }
}
