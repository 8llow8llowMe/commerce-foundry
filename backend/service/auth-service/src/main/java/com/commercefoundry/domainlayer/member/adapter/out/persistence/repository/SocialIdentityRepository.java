package com.commercefoundry.domainlayer.member.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.SocialIdentityEntity;
import com.commercefoundry.domainlayer.member.domain.model.SocialProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialIdentityRepository extends JpaRepository<SocialIdentityEntity, Long> {
    @org.springframework.data.jpa.repository.Query("select s.provider from SocialIdentityEntity s where s.memberId = :memberId order by s.provider")
    java.util.List<SocialProvider> findProvidersByMemberId(long memberId);
    Optional<SocialIdentityEntity> findByMemberIdAndProvider(long memberId, SocialProvider provider);
    Optional<SocialIdentityEntity> findByProviderAndSubject(SocialProvider provider, String subject);
}