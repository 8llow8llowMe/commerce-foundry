package com.commercefoundry.domainlayer.member.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.SocialIdentityEntity;
import com.commercefoundry.domainlayer.member.domain.model.SocialProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialIdentityRepository extends JpaRepository<SocialIdentityEntity, Long> {
    Optional<SocialIdentityEntity> findByProviderAndSubject(SocialProvider provider, String subject);
}