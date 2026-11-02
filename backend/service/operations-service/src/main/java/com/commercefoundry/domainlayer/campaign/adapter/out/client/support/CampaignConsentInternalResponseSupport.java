package com.commercefoundry.domainlayer.campaign.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.campaign.adapter.out.client.feign.CampaignConsentClient;
import com.commercefoundry.domainlayer.campaign.application.exception.CampaignErrorCode;
import com.commercefoundry.domainlayer.campaign.application.exception.CampaignException;
import com.commercefoundry.domainlayer.campaign.application.port.out.query.CampaignConsentQueryResult;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CampaignConsentInternalResponseSupport {
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public CampaignConsentQueryResult call(CampaignConsentClient client, List<Long> memberIds) {
        try {
            return circuitBreakerRegistry.circuitBreaker("auth-service").executeSupplier(
                () -> normalize(client.query(new CampaignConsentClient.ConsentRequest(memberIds)), memberIds));
        } catch (RuntimeException exception) {
            // Feign 예외에는 응답 원문이 있을 수 있어 cause·메시지·요청 목록을 전파하거나 로깅하지 않는다.
            throw unavailable();
        }
    }

    private CampaignConsentQueryResult normalize(Response<CampaignConsentClient.ConsentResponse> response, List<Long> expectedIds) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success()
            || response.dataBody() == null || response.dataBody().members() == null) {
            throw unavailable();
        }
        Set<Long> expected = Set.copyOf(expectedIds);
        Set<Long> seen = new HashSet<>();
        Set<Long> agreed = new HashSet<>();
        for (var member : response.dataBody().members()) {
            if (member == null || member.memberId() == null || member.marketingAgreed() == null) {
                throw unavailable();
            }
            Long id = Long.valueOf(member.memberId());
            if (!member.memberId().equals(id.toString()) || !expected.contains(id) || !seen.add(id)) {
                throw unavailable();
            }
            if (member.marketingAgreed()) {
                agreed.add(id);
            }
        }
        if (!seen.equals(expected)) {
            throw unavailable();
        }
        return new CampaignConsentQueryResult(agreed);
    }

    private static CampaignException unavailable() {
        return new CampaignException(CampaignErrorCode.CONSENT_SERVICE_UNAVAILABLE);
    }
}
