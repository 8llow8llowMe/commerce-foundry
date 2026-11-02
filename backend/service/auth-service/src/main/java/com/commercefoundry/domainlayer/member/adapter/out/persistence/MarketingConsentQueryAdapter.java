package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.MemberAgreementRepository;
import com.commercefoundry.domainlayer.member.application.port.out.MarketingConsentQueryPort;
import com.commercefoundry.domainlayer.member.application.queryresult.MarketingConsentQueryResult;
import com.commercefoundry.domainlayer.member.domain.model.AgreementType;
import com.commercefoundry.security.principal.MemberRole;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MarketingConsentQueryAdapter implements MarketingConsentQueryPort {

    private final MemberAgreementRepository memberAgreementRepository;

    @Override
    public MarketingConsentQueryResult findLatestAgreedCustomers(List<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return new MarketingConsentQueryResult(Set.of());
        }
        return new MarketingConsentQueryResult(Set.copyOf(
            memberAgreementRepository.findLatestAgreedMemberIds(memberIds, AgreementType.MARKETING, MemberRole.CUSTOMER)
        ));
    }
}
