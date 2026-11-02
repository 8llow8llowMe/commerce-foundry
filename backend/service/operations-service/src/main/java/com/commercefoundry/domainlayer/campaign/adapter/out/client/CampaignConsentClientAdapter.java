package com.commercefoundry.domainlayer.campaign.adapter.out.client;

import com.commercefoundry.domainlayer.campaign.adapter.out.client.feign.CampaignConsentClient;
import com.commercefoundry.domainlayer.campaign.adapter.out.client.support.CampaignConsentInternalResponseSupport;
import com.commercefoundry.domainlayer.campaign.application.port.out.CampaignConsentQueryPort;
import com.commercefoundry.domainlayer.campaign.application.port.out.query.CampaignConsentQueryResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CampaignConsentClientAdapter implements CampaignConsentQueryPort {
    private final CampaignConsentClient client;
    private final CampaignConsentInternalResponseSupport responseSupport;

    @Override
    public CampaignConsentQueryResult findConsents(List<Long> memberIds) {
        return responseSupport.call(client, memberIds);
    }
}
