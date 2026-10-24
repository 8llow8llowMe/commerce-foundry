package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.command.SignUpCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.MemberSignUpInfo;
import com.commercefoundry.domainlayer.member.application.port.out.MemberAgreementRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AgreementType;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.global.properties.AgreementProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 회원가입. 비밀번호 해시와 저장을 나눠 둔다 — 해시(BCrypt)는 의도적으로 느린 CPU 작업이라 DB 트랜잭션 밖에서 한다.
 */
@Component
@RequiredArgsConstructor
public class MemberSignUpProcessor {

    private final MemberRepositoryPort memberRepositoryPort;
    private final MemberAgreementRepositoryPort memberAgreementRepositoryPort;
    private final PasswordEncoder passwordEncoder;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final AgreementProperties agreementProperties;

    /** BCrypt 해시. 원문과 해시를 로그에 남기지 않는다. */
    public String hashPassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * 회원과 동의 이력 3줄을 한 트랜잭션으로 넣는다. 역할은 언제나 CUSTOMER 다.
     *
     * <p>Facade 가 아니라 여기서 트랜잭션을 건다 — 해시를 트랜잭션 안에서 하면 BCrypt 시간(수십~수백 ms)만큼 DB 커넥션을 쥐고 있게 된다.
     *
     * <p>{@link Member#normalizeEmail} 의 {@link IllegalArgumentException}(비었거나 정규화 뒤 254자 초과)은 잡지 않는다 — 요청 검증이 원문 길이({@code @Size})와
     * 정규화(앞뒤 공백 제거 · 소문자) 뒤 길이({@code @ValidNormalizedEmailLength}, #233)까지 AUTH_102 로 막아 여기에 닿지 않는다. 닿으면 요청 검증의 결함이다.
     *
     * @throws MemberException {@link MemberErrorCode#DUPLICATE_EMAIL} — 이미 가입된 이메일. 사전 조회를 함께 지난 동시 가입은 유니크 키가 막는다
     */
    @Transactional
    public MemberSignUpInfo register(SignUpCommand command, String passwordHash) {
        String email = Member.normalizeEmail(command.email());
        if (memberRepositoryPort.existsByEmail(email)) {
            throw new MemberException(MemberErrorCode.DUPLICATE_EMAIL);
        }

        Member member = memberRepositoryPort.save(Member.signUp(snowflakeIdGenerator.generateId(), email, passwordHash, command.name(), command.phone()));
        memberAgreementRepositoryPort.appendAll(command.agreements().toHistory(member.id(), snowflakeIdGenerator::generateId, this::termsVersionOf));
        return new MemberSignUpInfo(member.id());
    }

    private String termsVersionOf(AgreementType type) {
        AgreementProperties.Versions versions = agreementProperties.versions();
        return switch (type) {
            case TERMS_OF_SERVICE -> versions.termsOfService();
            case PRIVACY_POLICY -> versions.privacyPolicy();
            case MARKETING -> versions.marketing();
        };
    }
}
