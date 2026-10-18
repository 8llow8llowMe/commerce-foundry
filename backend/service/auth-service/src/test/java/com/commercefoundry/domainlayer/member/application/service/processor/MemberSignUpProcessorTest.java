package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.application.command.SignUpCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.MemberSignUpInfo;
import com.commercefoundry.domainlayer.member.application.port.out.MemberAgreementRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AgreementType;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.member.domain.model.MemberAgreement;
import com.commercefoundry.domainlayer.member.domain.model.SignUpAgreements;
import com.commercefoundry.global.properties.AgreementProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.principal.MemberRole;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class MemberSignUpProcessorTest {

    private static final AgreementProperties AGREEMENT_PROPERTIES =
        new AgreementProperties(new AgreementProperties.Versions("tos-2026-10", "privacy-2026-10", "marketing-2026-10"));
    private static final String HASH = "$2a$10$hash";

    @Mock
    private MemberRepositoryPort memberRepositoryPort;

    @Mock
    private MemberAgreementRepositoryPort memberAgreementRepositoryPort;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Captor
    private ArgumentCaptor<Member> memberCaptor;

    @Captor
    private ArgumentCaptor<List<MemberAgreement>> agreementsCaptor;

    private MemberSignUpProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new MemberSignUpProcessor(memberRepositoryPort, memberAgreementRepositoryPort, passwordEncoder, snowflakeIdGenerator, AGREEMENT_PROPERTIES);
    }

    @Test
    @DisplayName("비밀번호는 PasswordEncoder 로 해시한다")
    void hashPassword() {
        given(passwordEncoder.encode("mokoyuri2026!")).willReturn(HASH);

        assertThat(processor.hashPassword("mokoyuri2026!")).isEqualTo(HASH);
    }

    @Test
    @DisplayName("정규화한 이메일로 중복을 보고, CUSTOMER 회원과 동의 이력 3줄(서버 약관 버전)을 저장한다")
    void register() {
        given(memberRepositoryPort.existsByEmail("customer@example.com")).willReturn(false);
        given(snowflakeIdGenerator.generateId()).willReturn(1000L, 1001L, 1002L, 1003L);
        given(memberRepositoryPort.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        MemberSignUpInfo info = processor.register(command(" Customer@Example.com", new SignUpAgreements(true, true, true)), HASH);

        assertThat(info.memberId()).isEqualTo(1000L);
        verify(memberRepositoryPort).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue())
            .extracting(Member::id, Member::email, Member::passwordHash, Member::name, Member::phone, Member::role, Member::version)
            .containsExactly(1000L, "customer@example.com", HASH, "홍길동", "01012345678", MemberRole.CUSTOMER, null);

        verify(memberAgreementRepositoryPort).appendAll(agreementsCaptor.capture());
        assertThat(agreementsCaptor.getValue())
            .extracting(MemberAgreement::id, MemberAgreement::memberId, MemberAgreement::agreementType, MemberAgreement::agreed, MemberAgreement::termsVersion)
            .containsExactly(
                tuple(1001L, 1000L, AgreementType.TERMS_OF_SERVICE, true, "tos-2026-10"),
                tuple(1002L, 1000L, AgreementType.PRIVACY_POLICY, true, "privacy-2026-10"),
                tuple(1003L, 1000L, AgreementType.MARKETING, true, "marketing-2026-10")
            );
    }

    @Test
    @DisplayName("마케팅을 거절해도 false 한 줄을 남긴다")
    void marketingDeclinedIsRecorded() {
        given(snowflakeIdGenerator.generateId()).willReturn(1000L, 1001L, 1002L, 1003L);
        given(memberRepositoryPort.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        processor.register(command("customer@example.com", new SignUpAgreements(true, true, false)), HASH);

        verify(memberAgreementRepositoryPort).appendAll(agreementsCaptor.capture());
        assertThat(agreementsCaptor.getValue()).filteredOn(agreement -> agreement.agreementType() == AgreementType.MARKETING)
            .singleElement().extracting(MemberAgreement::agreed).isEqualTo(false);
    }

    @Test
    @DisplayName("이미 가입된 이메일(대소문자만 다른 것 포함)이면 AUTH_002 이고 아무것도 저장하지 않는다")
    void duplicateEmail() {
        given(memberRepositoryPort.existsByEmail("customer@example.com")).willReturn(true);

        assertThatThrownBy(() -> processor.register(command("CUSTOMER@example.com", new SignUpAgreements(true, true, false)), HASH))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);

        verify(memberRepositoryPort, never()).save(any());
        verifyNoInteractions(memberAgreementRepositoryPort, snowflakeIdGenerator);
    }

    private static SignUpCommand command(String email, SignUpAgreements agreements) {
        return new SignUpCommand(email, "mokoyuri2026!", "홍길동", "010-1234-5678", agreements);
    }
}
