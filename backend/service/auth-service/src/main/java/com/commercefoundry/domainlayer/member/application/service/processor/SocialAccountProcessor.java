package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.*;
import com.commercefoundry.domainlayer.member.application.port.out.*;
import com.commercefoundry.domainlayer.member.domain.model.*;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SocialAccountProcessor {
    private final MemberRepositoryPort members;
    private final SocialIdentityRepositoryPort identities;
    private final SnowflakeIdGenerator ids;

    @Transactional(readOnly = true)
    public List<SocialProvider> list(long memberId) {
        requireCustomer(members.findById(memberId));
        return identities.findProviders(memberId);
    }

    // Member lock serializes same-member retries; unique keys arbitrate different-member races.
    @Transactional(timeout = 5)
    public List<SocialProvider> link(long memberId, SocialProfile profile) {
        requireCustomer(members.findByIdForUpdate(memberId));
        var owner = identities.findMemberId(profile.provider(), profile.subject());
        if (owner.isPresent()) {
            if (owner.get() != memberId) {
                throw new MemberException(MemberErrorCode.SOCIAL_IDENTITY_CONFLICT);
            }
            return identities.findProviders(memberId);
        }
        if (identities.findSubject(memberId, profile.provider()).isPresent()) {
            throw new MemberException(MemberErrorCode.SOCIAL_IDENTITY_CONFLICT);
        }
        identities.append(ids.generateId(), memberId, profile.provider(), profile.subject());
        return identities.findProviders(memberId);
    }

    private void requireCustomer(java.util.Optional<Member> member) {
        member.filter(Member::isCustomer).orElseThrow(() -> new MemberException(MemberErrorCode.NOT_FOUND_MEMBER));
    }
}
