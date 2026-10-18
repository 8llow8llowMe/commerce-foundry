package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.application.command.MemberProfileUpdateCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.MemberProfileInfo;
import com.commercefoundry.domainlayer.member.application.port.out.MemberAgreementRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AgreementType;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.member.domain.model.MemberAgreement;
import com.commercefoundry.global.properties.AgreementProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.principal.MemberRole;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberProfileProcessorTest {

    private static final long MEMBER_ID = 4611686018427387904L;
    private static final AgreementProperties AGREEMENT_PROPERTIES =
        new AgreementProperties(new AgreementProperties.Versions("tos-2026-10", "privacy-2026-10", "marketing-2026-10"));
    private static final Member MEMBER = new Member(MEMBER_ID, "customer@example.com", "$2a$10$hash", "홍길동", "01012345678", MemberRole.CUSTOMER, 2L);

    @Mock
    private MemberRepositoryPort memberRepositoryPort;

    @Mock
    private MemberAgreementRepositoryPort memberAgreementRepositoryPort;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private MemberProfileProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new MemberProfileProcessor(memberRepositoryPort, memberAgreementRepositoryPort, snowflakeIdGenerator, AGREEMENT_PROPERTIES);
    }

    @Nested
    @DisplayName("조회")
    class GetProfile {

        @Test
        @DisplayName("마케팅 동의는 가장 최근 이력 값이다")
        void latestMarketing() {
            given(memberRepositoryPort.findById(MEMBER_ID)).willReturn(Optional.of(MEMBER));
            given(memberAgreementRepositoryPort.findLatest(MEMBER_ID, AgreementType.MARKETING)).willReturn(Optional.of(marketing(true)));

            assertThat(processor.getProfile(MEMBER_ID))
                .isEqualTo(new MemberProfileInfo(MEMBER_ID, "customer@example.com", "홍길동", "01012345678", true));
        }

        @Test
        @DisplayName("마케팅 이력이 없으면 동의하지 않은 것이다")
        void noHistoryMeansNotAgreed() {
            given(memberRepositoryPort.findById(MEMBER_ID)).willReturn(Optional.of(MEMBER));
            given(memberAgreementRepositoryPort.findLatest(MEMBER_ID, AgreementType.MARKETING)).willReturn(Optional.empty());

            assertThat(processor.getProfile(MEMBER_ID).marketingAgreed()).isFalse();
        }

        @Test
        @DisplayName("회원이 없으면 AUTH_003 이다")
        void notFound() {
            given(memberRepositoryPort.findById(MEMBER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> processor.getProfile(MEMBER_ID))
                .isInstanceOf(MemberException.class)
                .extracting(exception -> ((MemberException) exception).getErrorCode())
                .isEqualTo(MemberErrorCode.NOT_FOUND_MEMBER);
            verifyNoInteractions(memberAgreementRepositoryPort);
        }
    }

    @Nested
    @DisplayName("수정")
    class UpdateProfile {

        @BeforeEach
        void memberExists() {
            given(memberRepositoryPort.findById(MEMBER_ID)).willReturn(Optional.of(MEMBER));
        }

        @Test
        @DisplayName("이름 · 전화를 바꾸면 버전을 그대로 들고 저장한다 — 버전 비교는 저장소가 한다")
        void savesChangedProfile() {
            given(memberRepositoryPort.save(any())).willAnswer(invocation -> {
                Member saved = invocation.getArgument(0);
                return new Member(saved.id(), saved.email(), saved.passwordHash(), saved.name(), saved.phone(), saved.role(), saved.version() + 1);
            });
            given(memberAgreementRepositoryPort.findLatest(MEMBER_ID, AgreementType.MARKETING)).willReturn(Optional.of(marketing(false)));

            MemberProfileInfo info = processor.updateProfile(MEMBER_ID, new MemberProfileUpdateCommand("김철수", "", null));

            verify(memberRepositoryPort).save(new Member(MEMBER_ID, "customer@example.com", "$2a$10$hash", "김철수", null, MemberRole.CUSTOMER, 2L));
            assertThat(info).isEqualTo(new MemberProfileInfo(MEMBER_ID, "customer@example.com", "김철수", null, false));
            verify(memberAgreementRepositoryPort, never()).appendAll(any());
        }

        @Test
        @DisplayName("바뀐 것이 없으면 회원 행을 쓰지 않는다")
        void unchangedSkipsSave() {
            given(memberAgreementRepositoryPort.findLatest(MEMBER_ID, AgreementType.MARKETING)).willReturn(Optional.of(marketing(true)));

            processor.updateProfile(MEMBER_ID, new MemberProfileUpdateCommand(" 홍길동 ", "010-1234-5678", true));

            verify(memberRepositoryPort, never()).save(any());
            verify(memberRepositoryPort, never()).incrementVersion(any());
            verify(memberAgreementRepositoryPort, never()).appendAll(any());
        }

        @Test
        @DisplayName("마케팅만 바뀌면 이력을 한 줄 남기고(서버 약관 버전) 읽은 버전으로 회원 버전을 올린다 — 동시 수정이 AUTH_004 로 갈리게")
        void marketingChangeAppendsHistory() {
            given(memberAgreementRepositoryPort.findLatest(MEMBER_ID, AgreementType.MARKETING)).willReturn(Optional.of(marketing(true)));
            given(snowflakeIdGenerator.generateId()).willReturn(9001L);

            MemberProfileInfo info = processor.updateProfile(MEMBER_ID, new MemberProfileUpdateCommand(null, null, false));

            verify(memberAgreementRepositoryPort).appendAll(List.of(new MemberAgreement(9001L, MEMBER_ID, AgreementType.MARKETING, false, "marketing-2026-10")));
            verify(memberRepositoryPort).incrementVersion(MEMBER);
            verify(memberRepositoryPort, never()).save(any());
            assertThat(info.marketingAgreed()).isFalse();
        }

        @Test
        @DisplayName("이름과 마케팅이 함께 바뀌면 저장이 버전을 올린다 — 버전을 두 번 올리지 않는다")
        void profileAndMarketing() {
            given(memberRepositoryPort.save(any())).willAnswer(invocation -> invocation.getArgument(0));
            given(memberAgreementRepositoryPort.findLatest(MEMBER_ID, AgreementType.MARKETING)).willReturn(Optional.empty());
            given(snowflakeIdGenerator.generateId()).willReturn(9002L);

            processor.updateProfile(MEMBER_ID, new MemberProfileUpdateCommand("김철수", null, true));

            verify(memberRepositoryPort).save(any());
            verify(memberRepositoryPort, never()).incrementVersion(any());
            verify(memberAgreementRepositoryPort).appendAll(any());
        }

        @Test
        @DisplayName("마케팅만 바꿀 때 버전이 밀렸으면 AUTH_004 이고 이력을 남기지 않는다")
        void marketingConflict() {
            given(memberAgreementRepositoryPort.findLatest(MEMBER_ID, AgreementType.MARKETING)).willReturn(Optional.empty());
            willThrow(new MemberException(MemberErrorCode.MEMBER_UPDATE_CONFLICT)).given(memberRepositoryPort).incrementVersion(MEMBER);

            assertThatThrownBy(() -> processor.updateProfile(MEMBER_ID, new MemberProfileUpdateCommand(null, null, true)))
                .isInstanceOf(MemberException.class);
            verify(memberAgreementRepositoryPort, never()).appendAll(any());
        }

        @Test
        @DisplayName("이력이 없는 회원이 마케팅에 동의하면 한 줄 남기고, 동의하지 않음(false)을 보내면 남기지 않는다")
        void noHistory() {
            given(memberAgreementRepositoryPort.findLatest(MEMBER_ID, AgreementType.MARKETING)).willReturn(Optional.empty());

            processor.updateProfile(MEMBER_ID, new MemberProfileUpdateCommand(null, null, false));

            verify(memberAgreementRepositoryPort, never()).appendAll(any());
        }

        @Test
        @DisplayName("수정 충돌(AUTH_004)은 그대로 올리고 동의 이력을 남기지 않는다")
        void conflictPropagates() {
            given(memberRepositoryPort.save(any())).willThrow(new MemberException(MemberErrorCode.MEMBER_UPDATE_CONFLICT));

            assertThatThrownBy(() -> processor.updateProfile(MEMBER_ID, new MemberProfileUpdateCommand("김철수", null, true)))
                .isInstanceOf(MemberException.class)
                .extracting(exception -> ((MemberException) exception).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_UPDATE_CONFLICT);
            verify(memberAgreementRepositoryPort, never()).appendAll(any());
        }
    }

    private static MemberAgreement marketing(boolean agreed) {
        return new MemberAgreement(1L, MEMBER_ID, AgreementType.MARKETING, agreed, "marketing-2026-01");
    }
}
