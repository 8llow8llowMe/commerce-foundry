package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.admin.presenter.AdminAccountPresenter;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccessCutoffProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminAccountFacadeTest {

    private static final Instant NOW = Instant.parse("2026-10-26T01:00:00.500Z");
    private static final Instant CUTOFF = Instant.parse("2026-10-26T01:00:01Z");
    private static final Member ACTOR = Member.restore(7L, "owner@example.com", "hash", "점주", null, MemberRole.ADMIN, 0L);
    private static final Member CREATED = Member.admin(9L, "staff@example.com", "hash", "김운영");

    private final AdminAccountProcessor adminAccountProcessor = mock(AdminAccountProcessor.class);
    private final AdminAccessCutoffProcessor adminAccessCutoffProcessor = mock(AdminAccessCutoffProcessor.class);
    private final AdminAuditProcessor adminAuditProcessor = mock(AdminAuditProcessor.class);
    private final AdminAccountFacade facade = new AdminAccountFacade(
        adminAccountProcessor, adminAccessCutoffProcessor, adminAuditProcessor, new AdminAccountPresenter(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("운영자 추가는 이메일을 정규화하고, 성공 감사만 남긴다. 컷오프는 없다")
    void createNormalizesEmail() {
        given(adminAccountProcessor.hashPassword("shop-staff-2026")).willReturn("hash");
        given(adminAccountProcessor.create("staff@example.com", "hash", "김운영")).willReturn(CREATED);

        assertThat(facade.createAdmin(ACTOR.id(), "  STAFF@example.com ", "shop-staff-2026", "김운영").memberId())
            .isEqualTo(String.valueOf(CREATED.id()));

        verify(adminAuditProcessor).adminAccountCreated(ACTOR.id(), CREATED.id(), true);
        verifyNoInteractions(adminAccessCutoffProcessor);
    }

    @Test
    @DisplayName("이미 있는 이메일은 아이디 없이 실패 감사를 남기고 AUTH_002 를 다시 던진다")
    void duplicateCreateAuditsWithoutTarget() {
        given(adminAccountProcessor.hashPassword("shop-staff-2026")).willReturn("hash");
        given(adminAccountProcessor.create("staff@example.com", "hash", "김운영"))
            .willThrow(new MemberException(MemberErrorCode.DUPLICATE_EMAIL));

        assertThatThrownBy(() -> facade.createAdmin(ACTOR.id(), "staff@example.com", "shop-staff-2026", "김운영"))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);
        verify(adminAuditProcessor).adminAccountCreated(ACTOR.id(), null, false);
    }

    @Test
    @DisplayName("다른 운영자를 끄면 컷오프만 하고 지금 토큰은 막지 않는다")
    void deactivatingSomeoneElseKeepsCallerToken() {
        facade.deactivateAdmin(ACTOR.id(), 8L, "caller-jti");

        verify(adminAccountProcessor).deactivate(8L, NOW);
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
        doThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE))
            .when(adminAccessCutoffProcessor).cutOff(anyLong(), any());

        assertThatThrownBy(() -> facade.deactivateAdmin(ACTOR.id(), 8L, "caller-jti"))
            .isInstanceOf(MemberException.class);
        verify(adminAuditProcessor, never()).adminAccountDeactivated(anyLong(), anyLong(), eq(true));
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 AUTH_032 이고 저장 · 컷오프를 하지 않는다")
    void currentPasswordMismatch() {
        given(adminAccountProcessor.findActiveAdmin(ACTOR.id())).willReturn(ACTOR);
        given(adminAccountProcessor.passwordMatches(ACTOR, "wrong")).willReturn(false);

        assertThatThrownBy(() -> facade.changePassword(ACTOR.id(), "caller-jti", "wrong", "next-password-2026"))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.ADMIN_PASSWORD_MISMATCH);
        verify(adminAccountProcessor, never()).changePassword(anyLong(), any());
        verifyNoInteractions(adminAccessCutoffProcessor);
        verify(adminAuditProcessor).adminPasswordChanged(ACTOR.id(), false);
    }

    @Test
    @DisplayName("비밀번호를 바꾸면 다음 초 컷오프와 지금 토큰 차단 뒤에 성공 감사를 남긴다")
    void passwordChangeCutsOffThenAudits() {
        given(adminAccountProcessor.findActiveAdmin(ACTOR.id())).willReturn(ACTOR);
        given(adminAccountProcessor.passwordMatches(ACTOR, "current-password1")).willReturn(true);
        given(adminAccountProcessor.hashPassword("next-password-2026")).willReturn("new-hash");

        facade.changePassword(ACTOR.id(), "caller-jti", "current-password1", "next-password-2026");

        verify(adminAccountProcessor).changePassword(ACTOR.id(), "new-hash");
        verify(adminAccessCutoffProcessor).cutOff(ACTOR.id(), CUTOFF);
        verify(adminAccessCutoffProcessor).revokeCurrent("caller-jti");
        verify(adminAuditProcessor).adminPasswordChanged(ACTOR.id(), true);
    }
}
