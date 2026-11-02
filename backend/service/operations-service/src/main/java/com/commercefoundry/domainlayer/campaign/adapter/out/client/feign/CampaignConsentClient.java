package com.commercefoundry.domainlayer.campaign.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "${feign-client.target-services.auth-service:auth-service}", contextId = "campaignConsentClient",
    configuration = InternalFeignClientConfiguration.class)
public interface CampaignConsentClient {
    @PostMapping("/internal/v1/marketing-consents/query")
    Response<ConsentResponse> query(@RequestBody ConsentRequest request);

    record ConsentRequest(List<Long> memberIds) {
    }

    record ConsentResponse(List<Member> members) {
    }

    record Member(String memberId, Boolean marketingAgreed) {
    }
}
