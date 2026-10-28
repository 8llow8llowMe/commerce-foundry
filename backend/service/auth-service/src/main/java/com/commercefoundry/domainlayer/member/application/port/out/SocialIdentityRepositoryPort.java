package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.domainlayer.member.domain.model.SocialProvider;
import java.util.Optional;

public interface SocialIdentityRepositoryPort {
    Optional<Long> findMemberId(SocialProvider provider, String subject);
    java.util.List<SocialProvider> findProviders(long memberId);
    Optional<String> findSubject(long memberId, SocialProvider provider);
    void append(long id, long memberId, SocialProvider provider, String subject);
}