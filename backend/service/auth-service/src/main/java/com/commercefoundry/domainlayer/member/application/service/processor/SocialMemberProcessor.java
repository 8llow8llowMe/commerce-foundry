package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.MemberAgreementRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.SocialIdentityRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.SocialProfile;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.member.domain.model.SignUpAgreements;
import com.commercefoundry.global.properties.AgreementProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SocialMemberProcessor {
    private final SocialIdentityRepositoryPort identityRepository;
    private final MemberRepositoryPort memberRepository;
    private final MemberAgreementRepositoryPort agreementsRepository;
    private final AgreementProperties agreementProperties;
    private final SnowflakeIdGenerator ids;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public Optional<Member> find(SocialProfile profile) {
        return identityRepository.findMemberId(profile.provider(), profile.subject()).map(id ->
            memberRepository.findById(id).filter(Member::isCustomer)
                .orElseThrow(() -> new MemberException(MemberErrorCode.LOGIN_FAILED)));
    }

    /** BCrypt는 DB 트랜잭션 밖이다. 랜덤 비밀번호 원문은 반환하거나 저장하지 않는다. */
    public String hashRandomPassword() {
        return passwordEncoder.encode(SocialAuthorizationProcessor.randomToken());
    }

    /** 외부 provider I/O와 BCrypt가 끝난 뒤 회원 · identity · 약관을 함께 저장한다. */
    @Transactional
    public Member register(SocialProfile profile, SignUpAgreements agreements, String passwordHash) {
        if (agreements == null) {
            throw new MemberException(MemberErrorCode.SOCIAL_AGREEMENTS_REQUIRED);
        }
        if (!profile.usableEmail() || profile.email() == null || profile.email().isBlank()) {
            throw new MemberException(MemberErrorCode.SOCIAL_EMAIL_REQUIRED);
        }
        String email;
        try {
            email = Member.normalizeEmail(profile.email());
            if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new MemberException(MemberErrorCode.SOCIAL_EMAIL_REQUIRED);
        }
        if (memberRepository.existsByEmail(email)) {
            throw new MemberException(MemberErrorCode.DUPLICATE_EMAIL);
        }
        String name = profile.name();
        if (name == null || name.isBlank()) {
            name = "소셜 회원";
        }
        name = name.strip();
        if (name.length() > Member.NAME_MAX_LENGTH) {
            name = name.substring(0, Character.isHighSurrogate(name.charAt(Member.NAME_MAX_LENGTH - 1)) ? Member.NAME_MAX_LENGTH - 1 : Member.NAME_MAX_LENGTH);
        }
        Member member = memberRepository.save(Member.signUp(ids.generateId(), email, passwordHash, name, null));
        identityRepository.append(ids.generateId(), member.id(), profile.provider(), profile.subject());
        AgreementProperties.Versions versions = agreementProperties.versions();
        agreementsRepository.appendAll(agreements.toHistory(member.id(), ids::generateId, type -> switch (type) {
            case TERMS_OF_SERVICE -> versions.termsOfService();
            case PRIVACY_POLICY -> versions.privacyPolicy();
            case MARKETING -> versions.marketing();
        }));
        return member;
    }
}