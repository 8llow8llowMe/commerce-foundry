package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAccountItem;
import com.commercefoundry.domainlayer.member.adapter.in.admin.presenter.AdminAccountPresenter;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AdminAccountInfo;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditPort;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccessCutoffProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountQueryProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.principal.MemberRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class AdminAccountFacadeTest {

    private static final Instant NOW = Instant.parse("2026-10-26T01:00:00.500Z");
    private static final Instant CUTOFF = Instant.parse("2026-10-26T01:00:01Z");
    private static final Member ACTOR = Member.restore(7L, "owner@example.com", "hash", "점주", null, MemberRole.ADMIN, 0L);
    private static final Member CREATED = Member.admin(9L, "staff@example.com", "hash", "김운영");

    private final AdminAccountProcessor adminAccountProcessor = mock(AdminAccountProcessor.class);
    private final AdminAccountQueryProcessor adminAccountQueryProcessor = mock(AdminAccountQueryProcessor.class);
    private final AdminAccessCutoffProcessor adminAccessCutoffProcessor = mock(AdminAccessCutoffProcessor.class);
    private final AdminAuditProcessor adminAuditProcessor = mock(AdminAuditProcessor.class);
    private final AdminAccountFacade facade = facade(adminAuditProcessor, new AdminAccountPresenter());

    private AdminAccountFacade facade(AdminAuditProcessor auditProcessor, AdminAccountPresenter presenter) {
        return new AdminAccountFacade(adminAccountProcessor, adminAccountQueryProcessor, adminAccessCutoffProcessor, auditProcessor, presenter,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("운영자 추가는 이메일을 정규화하고, 성공 감사만 남긴다. 컷오프는 없다")
    void createNormalizesEmail() {
        given(adminAccountProcessor.hashPassword("shop-staff-2026")).willReturn("hash");
        given(adminAccountProcessor.create(ACTOR.id(), "staff@example.com", "hash", "김운영", null)).willReturn(CREATED);

        assertThat(facade.createAdmin(ACTOR.id(), "  STAFF@example.com ", "shop-staff-2026", "김운영", null).memberId())
            .isEqualTo(String.valueOf(CREATED.id()));

        verify(adminAuditProcessor).adminAccountCreated(ACTOR.id(), CREATED.id(), true);
        verifyNoInteractions(adminAccessCutoffProcessor);
    }

    @Test
    @DisplayName("운영자 추가에 역할을 주면 그대로 Processor 에 넘긴다 (#236)")
    void createPassesRole() {
        given(adminAccountProcessor.hashPassword("shop-staff-2026")).willReturn("hash");
        Member manager = Member.operator(10L, "manager@example.com", "hash", "김매니", MemberRole.MANAGER);
        given(adminAccountProcessor.create(ACTOR.id(), "manager@example.com", "hash", "김매니", MemberRole.MANAGER)).willReturn(manager);

        assertThat(facade.createAdmin(ACTOR.id(), "manager@example.com", "shop-staff-2026", "김매니", MemberRole.MANAGER).memberId()).isEqualTo("10");
        verify(adminAuditProcessor).adminAccountCreated(ACTOR.id(), 10L, true);
    }

    @Test
    @DisplayName("이미 있는 이메일은 아이디 없이 실패 감사를 남기고 AUTH_002 를 다시 던진다")
    void duplicateCreateAuditsWithoutTarget() {
        given(adminAccountProcessor.hashPassword("shop-staff-2026")).willReturn("hash");
        given(adminAccountProcessor.create(ACTOR.id(), "staff@example.com", "hash", "김운영", null))
            .willThrow(new MemberException(MemberErrorCode.DUPLICATE_EMAIL));

        assertCode(MemberErrorCode.DUPLICATE_EMAIL, () -> facade.createAdmin(ACTOR.id(), "staff@example.com", "shop-staff-2026", "김운영", null));
        verify(adminAuditProcessor).adminAccountCreated(ACTOR.id(), null, false);
    }

    @Test
    @DisplayName("운영자 목록은 마스킹한 페이지를 주고 조회 감사를 남긴다 (#236)")
    void searchMasksAndAudits() {
        AdminAccountInfo info = new AdminAccountInfo(11L, "manager@example.com", "김매니저", MemberRole.MANAGER, null, true, NOW);
        given(adminAccountQueryProcessor.search(true, 0, 20)).willReturn(new PageImpl<>(List.of(info), PageRequest.of(0, 20), 1));

        PageResponse<AdminAccountItem> page = facade.searchAdmins(ACTOR.id(), true, 0, 20);

        assertThat(page.contents()).singleElement().satisfies(row -> {
            assertThat(row.memberId()).isEqualTo("11");
            assertThat(row.email()).isEqualTo("ma***@e***.com");
            assertThat(row.name()).isEqualTo("김*저");
            assertThat(row.active()).isTrue();
            assertThat(row.otpEnrolled()).isTrue();
        });
        verify(adminAuditProcessor).operatorsSearched(ACTOR.id(), true, 0, 20);
    }

    @Test
    @DisplayName("다른 운영자를 끄면 컷오프만 하고 지금 토큰은 막지 않는다")
    void deactivatingSomeoneElseKeepsCallerToken() {
        facade.deactivateAdmin(ACTOR.id(), 8L, "caller-jti");

        verify(adminAccountProcessor).deactivate(ACTOR.id(), 8L, NOW);
        verify(adminAccessCutoffProcessor).cutOff(8L, CUTOFF);
        verify(adminAccessCutoffProcessor, never()).revokeCurrent(any());
        verify(adminAuditProcessor).adminAccountDeactivated(ACTOR.id(), 8L, true);
    }

    @Test
    @DisplayName("본인을 끄면 컷오프 뒤에 지금 토큰도 막는다")
    void selfDeactivationRevokesCurrentToken() {
        facade.deactivateAdmin(ACTOR.id(), ACTOR.id(), "caller-jti");

        verify(adminAccessCutoffProcessor).cutOff(ACTOR.id(), CUTOFF);
        verify(adminAccessCutoffProcessor).revokeCurrent("caller-jti");
        verify(adminAuditProcessor).adminAccountDeactivated(ACTOR.id(), ACTOR.id(), true);
    }

    @Test
    @DisplayName("컷오프 저장이 실패하면 성공 감사를 남기지 않는다")
    void cutoffFailureSkipsSuccessAudit() {
        MemberException original = new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        doThrow(original)
            .when(adminAccessCutoffProcessor).cutOff(anyLong(), any());

        assertThatThrownBy(() -> facade.deactivateAdmin(ACTOR.id(), 8L, "caller-jti"))
            .isSameAs(original);
        verify(adminAuditProcessor, never()).adminAccountDeactivated(anyLong(), anyLong(), eq(true));
        verify(adminAuditProcessor).adminAccountDeactivated(ACTOR.id(), 8L, false);
    }

    @Test
    @DisplayName("역할 변경은 저장 → 컷오프 → 성공 감사 순서다. 지금 토큰은 막지 않는다 (#236)")
    void roleChangeSavesThenCutsOffThenAudits() {
        facade.changeRole(ACTOR.id(), 8L, MemberRole.STAFF);

        InOrder order = inOrder(adminAccountProcessor, adminAccessCutoffProcessor, adminAuditProcessor);
        order.verify(adminAccountProcessor).changeRole(ACTOR.id(), 8L, MemberRole.STAFF);
        order.verify(adminAccessCutoffProcessor).cutOff(8L, CUTOFF);
        order.verify(adminAuditProcessor).adminRoleChanged(ACTOR.id(), 8L, true);
        verify(adminAccessCutoffProcessor, never()).revokeCurrent(any());
    }

    @Test
    @DisplayName("역할 저장 뒤 컷오프가 실패하면 503(AUTH_013) 그대로이고 감사는 실패만 남는다 (#236)")
    void roleChangeCutoffFailure() {
        MemberException original = new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        doThrow(original).when(adminAccessCutoffProcessor).cutOff(anyLong(), any());

        assertThatThrownBy(() -> facade.changeRole(ACTOR.id(), 8L, MemberRole.MANAGER)).isSameAs(original);
        verify(adminAccountProcessor).changeRole(ACTOR.id(), 8L, MemberRole.MANAGER);
        verify(adminAuditProcessor, never()).adminRoleChanged(anyLong(), anyLong(), eq(true));
        verify(adminAuditProcessor).adminRoleChanged(ACTOR.id(), 8L, false);
    }

    @Test
    @DisplayName("역할 변경이 거절되면 컷오프 없이 실패 감사를 남기고 같은 예외를 던진다 (#236)")
    void roleChangeRejected() {
        doThrow(new MemberException(MemberErrorCode.OPERATOR_ROLE_ISSUANCE_DISABLED))
            .when(adminAccountProcessor).changeRole(ACTOR.id(), 8L, MemberRole.MANAGER);

        assertCode(MemberErrorCode.OPERATOR_ROLE_ISSUANCE_DISABLED, () -> facade.changeRole(ACTOR.id(), 8L, MemberRole.MANAGER));
        verifyNoInteractions(adminAccessCutoffProcessor);
        verify(adminAuditProcessor).adminRoleChanged(ACTOR.id(), 8L, false);
    }

    @Test
    @DisplayName("상태 false 는 저장 → 컷오프 → ADMIN_DEACTIVATE 성공 감사다 (#236)")
    void statusFalseDeactivates() {
        facade.changeStatus(ACTOR.id(), 8L, false);

        InOrder order = inOrder(adminAccountProcessor, adminAccessCutoffProcessor, adminAuditProcessor);
        order.verify(adminAccountProcessor).changeStatus(ACTOR.id(), 8L, false, NOW);
        order.verify(adminAccessCutoffProcessor).cutOff(8L, CUTOFF);
        order.verify(adminAuditProcessor).adminAccountDeactivated(ACTOR.id(), 8L, true);
    }

    @Test
    @DisplayName("상태 true 는 컷오프 없이 ADMIN_REACTIVATE 성공 감사다 (#236)")
    void statusTrueReactivatesWithoutCutoff() {
        facade.changeStatus(ACTOR.id(), 8L, true);

        verify(adminAccountProcessor).changeStatus(ACTOR.id(), 8L, true, NOW);
        verifyNoInteractions(adminAccessCutoffProcessor);
        verify(adminAuditProcessor).adminAccountReactivated(ACTOR.id(), 8L, true);
        verifyNoMoreInteractions(adminAuditProcessor);
    }

    @Test
    @DisplayName("정지 저장 뒤 컷오프가 실패하면 503 그대로이고 감사는 실패만 남는다 (#236)")
    void statusCutoffFailure() {
        MemberException original = new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        doThrow(original).when(adminAccessCutoffProcessor).cutOff(anyLong(), any());

        assertThatThrownBy(() -> facade.changeStatus(ACTOR.id(), 8L, false)).isSameAs(original);
        verify(adminAuditProcessor).adminAccountDeactivated(ACTOR.id(), 8L, false);
        verifyNoMoreInteractions(adminAuditProcessor);
    }

    @Test
    @DisplayName("재활성화가 거절되면 ADMIN_REACTIVATE 실패 감사를 남긴다 (#236)")
    void reactivationRejected() {
        doThrow(new MemberException(MemberErrorCode.ADMIN_SELF_MANAGEMENT)).when(adminAccountProcessor).changeStatus(ACTOR.id(), ACTOR.id(), true, NOW);

        assertCode(MemberErrorCode.ADMIN_SELF_MANAGEMENT, () -> facade.changeStatus(ACTOR.id(), ACTOR.id(), true));
        verify(adminAuditProcessor).adminAccountReactivated(ACTOR.id(), ACTOR.id(), false);
        verifyNoMoreInteractions(adminAuditProcessor);
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 AUTH_032 이고 저장 · 컷오프를 하지 않는다")
    void currentPasswordMismatch() {
        given(adminAccountProcessor.findActiveOperator(ACTOR.id())).willReturn(ACTOR);
        given(adminAccountProcessor.passwordMatches(ACTOR, "wrong")).willReturn(false);

        assertCode(MemberErrorCode.ADMIN_PASSWORD_MISMATCH, () -> facade.changePassword(ACTOR.id(), "caller-jti", "wrong", "next-password-2026"));
        verify(adminAccountProcessor, never()).changePassword(anyLong(), any());
        verifyNoInteractions(adminAccessCutoffProcessor);
        verify(adminAuditProcessor).adminPasswordChanged(ACTOR.id(), false);
        verifyNoMoreInteractions(adminAuditProcessor);
    }

    @Test
    void businessExceptionIdentitySurvivesFailingAuditWriter() {
        AdminAuditPort port = mock(AdminAuditPort.class);
        AdminAccountFacade isolated = facade(new AdminAuditProcessor(port), new AdminAccountPresenter());
        RuntimeException original = new IllegalStateException("business-secret");
        given(adminAccountProcessor.hashPassword("password")).willThrow(original);
        doThrow(new MemberException(MemberErrorCode.DUPLICATE_EMAIL)).when(port).adminAccountCreated(ACTOR.id(), null, false);

        assertThatThrownBy(() -> isolated.createAdmin(ACTOR.id(), "staff@example.com", "password", "운영", null)).isSameAs(original);
        verify(port).adminAccountCreated(ACTOR.id(), null, false);
        verifyNoMoreInteractions(port);
    }

    @Test
    void successfulWriterFailureDoesNotBecomeBusinessFailure() {
        AdminAuditPort port = mock(AdminAuditPort.class);
        AdminAccountFacade isolated = facade(new AdminAuditProcessor(port), new AdminAccountPresenter());
        given(adminAccountProcessor.create(anyLong(), any(), any(), any(), any())).willReturn(CREATED);
        doThrow(new MemberException(MemberErrorCode.DUPLICATE_EMAIL)).when(port).adminAccountCreated(ACTOR.id(), CREATED.id(), true);

        assertThat(isolated.createAdmin(ACTOR.id(), "staff@example.com", "password", "운영", null).memberId()).isEqualTo("9");
        verify(port).adminAccountCreated(ACTOR.id(), CREATED.id(), true);
        verifyNoMoreInteractions(port);
    }

    @Test
    void presenterFailureDoesNotReverseCommittedCreateAudit() {
        AdminAccountPresenter presenter = mock(AdminAccountPresenter.class);
        AdminAccountFacade isolated = facade(adminAuditProcessor, presenter);
        given(adminAccountProcessor.create(anyLong(), any(), any(), any(), any())).willReturn(CREATED);
        MemberException original = new MemberException(MemberErrorCode.DUPLICATE_EMAIL);
        given(presenter.toCreated(CREATED.id())).willThrow(original);

        assertThatThrownBy(() -> isolated.createAdmin(ACTOR.id(), "staff@example.com", "password", "운영", null)).isSameAs(original);
        verify(adminAuditProcessor).adminAccountCreated(ACTOR.id(), CREATED.id(), true);
        verifyNoMoreInteractions(adminAuditProcessor);
    }

    @Test
    @DisplayName("비밀번호를 바꾸면 다음 초 컷오프와 지금 토큰 차단 뒤에 성공 감사를 남긴다")
    void passwordChangeCutsOffThenAudits() {
        given(adminAccountProcessor.findActiveOperator(ACTOR.id())).willReturn(ACTOR);
        given(adminAccountProcessor.passwordMatches(ACTOR, "current-password1")).willReturn(true);
        given(adminAccountProcessor.hashPassword("next-password-2026")).willReturn("new-hash");

        facade.changePassword(ACTOR.id(), "caller-jti", "current-password1", "next-password-2026");

        verify(adminAccountProcessor).changePassword(ACTOR.id(), "new-hash");
        verify(adminAccessCutoffProcessor).cutOff(ACTOR.id(), CUTOFF);
        verify(adminAccessCutoffProcessor).revokeCurrent("caller-jti");
        verify(adminAuditProcessor).adminPasswordChanged(ACTOR.id(), true);
    }

    private static void assertCode(MemberErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(code);
    }
}
