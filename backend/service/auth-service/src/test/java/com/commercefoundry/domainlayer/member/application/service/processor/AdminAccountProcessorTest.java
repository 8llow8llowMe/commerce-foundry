package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.global.properties.OperatorRoleProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.principal.MemberRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminAccountProcessorTest {

    private static final Instant AT = Instant.parse("2026-10-26T01:00:00Z");
    private static final Member OWNER = admin(1L);
    private static final Member OTHER_ADMIN = admin(2L);
    private static final Member NEW_OWNER = operator(3L, MemberRole.OWNER);
    private static final Member MANAGER = operator(4L, MemberRole.MANAGER);
    private static final Member STAFF_ROLE = operator(5L, MemberRole.STAFF);

    private final MemberRepositoryPort memberRepositoryPort = mock(MemberRepositoryPort.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final SnowflakeIdGenerator snowflakeIdGenerator = mock(SnowflakeIdGenerator.class);
    private AdminAccountProcessor processor;

    @BeforeEach
    void setUp() {
        processor = processor(true);
        given(memberRepositoryPort.save(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(memberRepositoryPort.findByIdForUpdate(OWNER.id())).willReturn(Optional.of(OWNER));
    }

    private AdminAccountProcessor processor(boolean issuanceEnabled) {
        return new AdminAccountProcessor(memberRepositoryPort, passwordEncoder, snowflakeIdGenerator, new OperatorRoleProperties(issuanceEnabled));
    }

    @Test
    @DisplayName("이메일이 없으면 관리자 계정을 만든다 — 고객으로 올리지 않는다")
    void createsAdmin() {
        given(memberRepositoryPort.existsByEmail("staff@example.com")).willReturn(false);
        given(snowflakeIdGenerator.generateId()).willReturn(9L);

        Member created = processor.create(OWNER.id(), "staff@example.com", "hash", "  김운영  ", null);

        assertThat(created.id()).isEqualTo(9L);
        assertThat(created.email()).isEqualTo("staff@example.com");
        assertThat(created.name()).isEqualTo("김운영");
        assertThat(created.role()).isEqualTo(MemberRole.ADMIN);
        assertThat(created.isActiveOperator()).isTrue();
        assertThat(created.phone()).isNull();
    }

    @Test
    @DisplayName("역할 없는 추가는 발급 플래그가 꺼져 있어도 ADMIN 으로 만든다 (#236)")
    void createsAdminWithIssuanceDisabled() {
        given(snowflakeIdGenerator.generateId()).willReturn(9L);

        assertThat(processor(false).create(OWNER.id(), "staff@example.com", "hash", "김운영", null).role()).isEqualTo(MemberRole.ADMIN);
    }

    @Test
    @DisplayName("이미 있는 이메일(고객 포함)은 AUTH_002 이고 저장하지 않는다")
    void duplicateEmail() {
        given(memberRepositoryPort.existsByEmail("staff@example.com")).willReturn(true);

        assertCode(MemberErrorCode.DUPLICATE_EMAIL, () -> processor.create(OWNER.id(), "staff@example.com", "hash", "김운영", null));
        verify(memberRepositoryPort, never()).save(any());
    }

    @Nested
    @DisplayName("역할 없는 추가(ADMIN)도 행위자를 DB 로 다시 판정한다 — 활성 대표 관리자만 (#236 리뷰)")
    class CreateActorRecheck {

        @Test
        @DisplayName("행위자 행은 잠가 읽는다")
        void readsActorWithLock() {
            given(snowflakeIdGenerator.generateId()).willReturn(9L);

            processor.create(OWNER.id(), "staff@example.com", "hash", "김운영", null);

            verify(memberRepositoryPort).findByIdForUpdate(OWNER.id());
        }

        @Test
        @DisplayName("매니저로 강등된 옛 대표 관리자(토큰은 아직 유효)는 403 AUTH_046 이고 저장하지 않는다")
        void demotedFormerOwnerIsRejected() {
            Member demoted = OWNER.changeRole(MemberRole.MANAGER);
            given(memberRepositoryPort.findByIdForUpdate(OWNER.id())).willReturn(Optional.of(demoted));

            assertCode(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN, () -> processor.create(OWNER.id(), "staff@example.com", "hash", "김운영", null));
            verify(memberRepositoryPort, never()).existsByEmail(any());
            verify(memberRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("정지된 옛 대표 관리자 · 없는 행위자도 403 AUTH_046 이다")
        void deactivatedOrMissingActorIsRejected() {
            given(memberRepositoryPort.findByIdForUpdate(OWNER.id())).willReturn(Optional.of(OWNER.deactivate(AT)));
            given(memberRepositoryPort.findByIdForUpdate(99L)).willReturn(Optional.empty());

            assertCode(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN, () -> processor.create(OWNER.id(), "staff@example.com", "hash", "김운영", null));
            assertCode(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN, () -> processor.create(99L, "staff@example.com", "hash", "김운영", null));
            verify(memberRepositoryPort, never()).save(any());
        }
    }

    @Nested
    @DisplayName("역할을 지정한 운영자 추가 (#236)")
    class CreateWithRole {

        @Test
        @DisplayName("대표 관리자는 매니저를 만든다")
        void ownerCreatesManager() {
            given(snowflakeIdGenerator.generateId()).willReturn(9L);

            assertThat(processor.create(OWNER.id(), "manager@example.com", "hash", "김매니", MemberRole.MANAGER).role()).isEqualTo(MemberRole.MANAGER);
        }

        @Test
        @DisplayName("발급 플래그가 꺼져 있으면 409 AUTH_049 이고 저장하지 않는다")
        void issuanceDisabled() {
            assertCode(MemberErrorCode.OPERATOR_ROLE_ISSUANCE_DISABLED,
                () -> processor(false).create(OWNER.id(), "manager@example.com", "hash", "김매니", MemberRole.MANAGER));
            verify(memberRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("매니저는 대표 관리자 역할을 줄 수 없다 — 403 AUTH_046")
        void managerCannotCreateOwner() {
            given(memberRepositoryPort.findByIdForUpdate(MANAGER.id())).willReturn(Optional.of(MANAGER));

            assertCode(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN,
                () -> processor.create(MANAGER.id(), "owner@example.com", "hash", "김대표", MemberRole.OWNER));
            verify(memberRepositoryPort, never()).save(any());
        }
    }

    @Test
    @DisplayName("활성 운영자가 둘 이상이면 대상만 끈다")
    void deactivatesWhenAnotherAdminRemains() {
        given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER, OTHER_ADMIN));

        processor.deactivate(OWNER.id(), OTHER_ADMIN.id(), AT);

        verify(memberRepositoryPort).save(argThat(saved -> saved.id() == OTHER_ADMIN.id() && AT.equals(saved.deactivatedAt())));
    }

    @Test
    @DisplayName("마지막 활성 운영자는 AUTH_031 이고 저장하지 않는다")
    void lastAdminIsRejected() {
        given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER));

        assertCode(MemberErrorCode.LAST_ACTIVE_ADMIN, () -> processor.deactivate(OWNER.id(), OWNER.id(), AT));
        verify(memberRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("대표 관리자가 한 명이면 매니저가 남아 있어도 그 대표 관리자는 끌 수 없다 — AUTH_031")
    void lastOwnerIsRejectedEvenWithManager() {
        given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(NEW_OWNER, MANAGER));

        assertCode(MemberErrorCode.LAST_ACTIVE_ADMIN, () -> processor.deactivate(NEW_OWNER.id(), NEW_OWNER.id(), AT));
        verify(memberRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("대표 관리자가 한 명뿐이어도 매니저는 끌 수 있다")
    void managerIsDeactivatedWithSingleOwner() {
        given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(NEW_OWNER, MANAGER));

        processor.deactivate(NEW_OWNER.id(), MANAGER.id(), AT);

        verify(memberRepositoryPort).save(argThat(saved -> saved.id() == MANAGER.id() && AT.equals(saved.deactivatedAt())));
    }

    @Test
    @DisplayName("마지막 남은 운영자가 스태프 한 명이어도 대표 관리자가 아니면 끌 수 있다 — 가드는 대표 관리자 수만 센다")
    void lastStaffIsNotGuarded() {
        given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(STAFF_ROLE));

        processor.deactivate(STAFF_ROLE.id(), STAFF_ROLE.id(), AT);

        verify(memberRepositoryPort).save(argThat(saved -> saved.id() == STAFF_ROLE.id() && AT.equals(saved.deactivatedAt())));
    }

    @Test
    @DisplayName("기존 ADMIN 과 OWNER 는 같은 대표 관리자로 센다 — 한 명이 남으면 다른 한 명은 끌 수 있다")
    void adminAndOwnerCountTogether() {
        given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER, NEW_OWNER, MANAGER));

        processor.deactivate(OWNER.id(), NEW_OWNER.id(), AT);

        verify(memberRepositoryPort).save(argThat(saved -> saved.id() == NEW_OWNER.id() && AT.equals(saved.deactivatedAt())));
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"MANAGER", "STAFF"})
    @DisplayName("대표 관리자 계정은 매니저 · 스태프가 끌 수 없다 — AUTH_046 이고 저장하지 않는다 (#236)")
    void nonOwnerCannotDeactivateOwner(MemberRole actorRole) {
        Member actor = operator(6L, actorRole);
        given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER, NEW_OWNER, actor));

        assertCode(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN, () -> processor.deactivate(actor.id(), NEW_OWNER.id(), AT));
        verify(memberRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("행위자가 활성 운영자 목록에 없으면(이미 꺼짐) 대표 관리자 계정을 끄지 못한다 — AUTH_046 (#236)")
    void inactiveActorCannotDeactivateOwner() {
        given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER, NEW_OWNER));

        assertCode(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN, () -> processor.deactivate(99L, NEW_OWNER.id(), AT));
        verify(memberRepositoryPort, never()).save(any());
    }

    @Nested
    @DisplayName("역할 변경 (#236)")
    class ChangeRole {

        @Test
        @DisplayName("대표 관리자가 다른 운영자의 역할을 바꾼다 — 활성 운영자 전부를 잠가 읽는다")
        void changesRole() {
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER, MANAGER));

            processor.changeRole(OWNER.id(), MANAGER.id(), MemberRole.STAFF);

            verify(memberRepositoryPort).findActiveOperatorsForUpdate();
            verify(memberRepositoryPort).save(argThat(saved -> saved.id() == MANAGER.id() && saved.role() == MemberRole.STAFF));
        }

        @Test
        @DisplayName("다른 대표 관리자가 남아 있으면 대표 관리자를 매니저로 내린다")
        void demotesOwnerWhenAnotherRemains() {
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER, NEW_OWNER));

            processor.changeRole(OWNER.id(), NEW_OWNER.id(), MemberRole.MANAGER);

            verify(memberRepositoryPort).save(argThat(saved -> saved.id() == NEW_OWNER.id() && saved.role() == MemberRole.MANAGER));
        }

        @Test
        @DisplayName("같은 역할이면 저장하지 않고 성공한다")
        void sameRoleIsNoOp() {
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER, MANAGER));

            processor.changeRole(OWNER.id(), MANAGER.id(), MemberRole.MANAGER);

            verify(memberRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("발급 플래그가 꺼져 있으면 409 AUTH_049 이고 잠그지도 않는다")
        void issuanceDisabled() {
            assertCode(MemberErrorCode.OPERATOR_ROLE_ISSUANCE_DISABLED, () -> processor(false).changeRole(OWNER.id(), MANAGER.id(), MemberRole.STAFF));
            verify(memberRepositoryPort, never()).findActiveOperatorsForUpdate();
            verify(memberRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("본인 역할은 400 AUTH_047 이고 잠그지 않는다")
        void selfIsRejected() {
            assertCode(MemberErrorCode.ADMIN_SELF_MANAGEMENT, () -> processor.changeRole(OWNER.id(), OWNER.id(), MemberRole.MANAGER));
            verify(memberRepositoryPort, never()).findActiveOperatorsForUpdate();
        }

        @Test
        @DisplayName("없거나 꺼진 대상은 404 AUTH_033 이다")
        void missingTarget() {
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER));

            assertCode(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND, () -> processor.changeRole(OWNER.id(), 99L, MemberRole.STAFF));
            verify(memberRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("비대표가 마지막 대표 관리자를 내리려 하면 409 AUTH_048 이 아니라 403 AUTH_046 이다 — 마지막 대표 여부를 비대표에게 드러내지 않는다."
            + " 048 은 지금 규칙에서 정상 경로로 닿지 않는 방어이고 판정은 MemberTest#lastActiveOwner 가 본다")
        void lastOwnerDemotionByNonOwnerIsForbidden() {
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(NEW_OWNER, MANAGER));

            assertCode(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN, () -> processor.changeRole(MANAGER.id(), NEW_OWNER.id(), MemberRole.STAFF));
            verify(memberRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("마지막 대표 관리자를 OWNER 로 바꾸는 것(ADMIN → OWNER)은 강등이 아니라 허용한다")
        void lastAdminToOwnerIsAllowed() {
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER, NEW_OWNER));

            processor.changeRole(NEW_OWNER.id(), OWNER.id(), MemberRole.OWNER);

            verify(memberRepositoryPort).save(argThat(saved -> saved.id() == OWNER.id() && saved.role() == MemberRole.OWNER));
        }

        @ParameterizedTest
        @EnumSource(value = MemberRole.class, names = {"MANAGER", "STAFF"})
        @DisplayName("매니저 · 스태프는 대표 관리자 계정의 역할을 바꾸지 못한다 — 403 AUTH_046")
        void nonOwnerCannotChangeOwner(MemberRole actorRole) {
            Member actor = operator(6L, actorRole);
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER, NEW_OWNER, actor));

            assertCode(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN, () -> processor.changeRole(actor.id(), NEW_OWNER.id(), MemberRole.MANAGER));
            verify(memberRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("매니저는 스태프를 매니저로 올릴 수 있지만 대표 관리자로는 올리지 못한다 — 403 AUTH_046")
        void managerCannotGrantOwner() {
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER, MANAGER, STAFF_ROLE));

            processor.changeRole(MANAGER.id(), STAFF_ROLE.id(), MemberRole.MANAGER);
            assertCode(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN, () -> processor.changeRole(MANAGER.id(), STAFF_ROLE.id(), MemberRole.OWNER));
        }

        @Test
        @DisplayName("스태프는 자기보다 높은 매니저 역할을 주지 못한다 — 403 AUTH_046")
        void staffCannotGrantManager() {
            Member otherStaff = operator(8L, MemberRole.STAFF);
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER, STAFF_ROLE, otherStaff));

            assertCode(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN, () -> processor.changeRole(STAFF_ROLE.id(), otherStaff.id(), MemberRole.MANAGER));
            verify(memberRepositoryPort, never()).save(any());
        }
    }

    @Nested
    @DisplayName("상태 변경 (#236)")
    class ChangeStatus {

        @Test
        @DisplayName("false 면 활성 운영자를 끈다")
        void deactivates() {
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER, MANAGER));

            processor.changeStatus(OWNER.id(), MANAGER.id(), false, AT);

            verify(memberRepositoryPort).save(argThat(saved -> saved.id() == MANAGER.id() && AT.equals(saved.deactivatedAt())));
        }

        @Test
        @DisplayName("true 면 꺼진 운영자를 그 행을 잠가 읽어 다시 켠다")
        void reactivates() {
            Member former = MANAGER.deactivate(AT);
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER));
            given(memberRepositoryPort.findByIdForUpdate(former.id())).willReturn(Optional.of(former));

            processor.changeStatus(OWNER.id(), former.id(), true, AT);

            verify(memberRepositoryPort).save(argThat(saved -> saved.id() == former.id() && saved.deactivatedAt() == null));
        }

        @Test
        @DisplayName("같은 상태면 저장하지 않고 성공한다 — 이미 켜진 계정 켜기 · 이미 꺼진 계정 끄기")
        void sameStatusIsNoOp() {
            Member former = STAFF_ROLE.deactivate(AT);
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER, MANAGER));
            given(memberRepositoryPort.findByIdForUpdate(former.id())).willReturn(Optional.of(former));

            processor.changeStatus(OWNER.id(), MANAGER.id(), true, AT);
            processor.changeStatus(OWNER.id(), former.id(), false, AT);

            verify(memberRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("본인 상태는 400 AUTH_047 이다")
        void selfIsRejected() {
            assertCode(MemberErrorCode.ADMIN_SELF_MANAGEMENT, () -> processor.changeStatus(OWNER.id(), OWNER.id(), false, AT));
            verify(memberRepositoryPort, never()).findActiveOperatorsForUpdate();
        }

        @Test
        @DisplayName("없거나 고객인 대상은 404 AUTH_033 이다")
        void missingOrCustomer() {
            Member customer = Member.restore(20L, "c@example.com", "hash", "고객", null, MemberRole.CUSTOMER, 0L);
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER));
            given(memberRepositoryPort.findByIdForUpdate(customer.id())).willReturn(Optional.of(customer));
            given(memberRepositoryPort.findByIdForUpdate(99L)).willReturn(Optional.empty());

            assertCode(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND, () -> processor.changeStatus(OWNER.id(), customer.id(), true, AT));
            assertCode(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND, () -> processor.changeStatus(OWNER.id(), 99L, false, AT));
            verify(memberRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("비대표가 마지막 대표 관리자를 끄려 하면 409 AUTH_031 이 아니라 403 AUTH_046 이다 — 마지막 대표 여부를 드러내지 않는다(031 은 방어)")
        void lastOwnerByNonOwnerIsForbidden() {
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(NEW_OWNER, MANAGER));

            assertCode(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN, () -> processor.changeStatus(MANAGER.id(), NEW_OWNER.id(), false, AT));
            verify(memberRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("꺼진 대표 관리자 계정은 매니저가 다시 켜지 못한다 — 403 AUTH_046")
        void managerCannotReactivateOwner() {
            Member formerOwner = operator(7L, MemberRole.OWNER).deactivate(AT);
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER, MANAGER));
            given(memberRepositoryPort.findByIdForUpdate(formerOwner.id())).willReturn(Optional.of(formerOwner));

            assertCode(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN, () -> processor.changeStatus(MANAGER.id(), formerOwner.id(), true, AT));
            verify(memberRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("상태 변경은 발급 플래그와 무관하다")
        void independentOfIssuanceFlag() {
            given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER, MANAGER));

            processor(false).changeStatus(OWNER.id(), MANAGER.id(), false, AT);

            verify(memberRepositoryPort).save(argThat(saved -> saved.id() == MANAGER.id() && AT.equals(saved.deactivatedAt())));
        }
    }

    @Test
    @DisplayName("매니저 · 스태프 계정의 비밀번호도 바꾼다 — 운영자 역할 전체가 대상이다")
    void changesOperatorPassword() {
        given(memberRepositoryPort.findByIdForUpdate(MANAGER.id())).willReturn(Optional.of(MANAGER));

        processor.changePassword(MANAGER.id(), "new-hash");

        verify(memberRepositoryPort).save(argThat(saved -> saved.id() == MANAGER.id() && "new-hash".equals(saved.passwordHash())));
    }

    @Test
    @DisplayName("없거나 이미 꺼진 계정은 AUTH_033 이다")
    void missingAdminIsNotFound() {
        given(memberRepositoryPort.findActiveOperatorsForUpdate()).willReturn(List.of(OWNER));

        assertCode(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND, () -> processor.deactivate(OWNER.id(), 99L, AT));
        verify(memberRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("활성 운영자의 비밀번호 해시만 바꾼다")
    void changesPassword() {
        given(memberRepositoryPort.findByIdForUpdate(OWNER.id())).willReturn(Optional.of(OWNER));

        processor.changePassword(OWNER.id(), "new-hash");

        verify(memberRepositoryPort).save(argThat(saved -> saved.id() == OWNER.id()
            && "new-hash".equals(saved.passwordHash())
            && saved.deactivatedAt() == null));
    }

    @Test
    @DisplayName("꺼진 운영자의 비밀번호는 바꾸지 않는다 — AUTH_033")
    void deactivatedPasswordChangeIsNotFound() {
        Member deactivated = OWNER.deactivate(AT);
        given(memberRepositoryPort.findByIdForUpdate(deactivated.id())).willReturn(Optional.of(deactivated));

        assertCode(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND, () -> processor.changePassword(deactivated.id(), "new-hash"));
        verify(memberRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("인코더가 거절하면 현재 비밀번호는 틀린 것이다")
    void encoderRejectionIsMismatch() {
        given(passwordEncoder.matches(any(), any())).willThrow(new IllegalArgumentException("rejected"));

        assertThat(processor.passwordMatches(OWNER, "x".repeat(100))).isFalse();
    }

    private static Member admin(long id) {
        return operator(id, MemberRole.ADMIN);
    }

    private static Member operator(long id, MemberRole role) {
        return Member.restore(id, id + "@example.com", "hash-" + id, "운영", null, role, 0L);
    }

    private static void assertCode(MemberErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(code);
    }
}
