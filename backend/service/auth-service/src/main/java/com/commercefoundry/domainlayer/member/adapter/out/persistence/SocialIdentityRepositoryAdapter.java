package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.SocialIdentityEntity;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.SocialIdentityRepository;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.port.out.SocialIdentityRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.SocialProvider;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SocialIdentityRepositoryAdapter implements SocialIdentityRepositoryPort {
    private final SocialIdentityRepository repository;

    @Override
    public Optional<Long> findMemberId(SocialProvider provider, String subject) {
        return repository.findByProviderAndSubject(provider, subject).map(SocialIdentityEntity::getMemberId);
    }

    @Override
    public void append(long id, long memberId, SocialProvider provider, String subject) {
        try {
            repository.saveAndFlush(SocialIdentityEntity.builder().id(id).memberId(memberId).provider(provider).subject(subject).build());
        } catch (DataIntegrityViolationException conflict) {
            if (ConstraintViolations.violates(conflict, SocialIdentityEntity.UK_PROVIDER_SUBJECT)) {
                throw new MemberException(MemberErrorCode.SOCIAL_IDENTITY_CONFLICT);
            }
            throw conflict;
        }
    }
}