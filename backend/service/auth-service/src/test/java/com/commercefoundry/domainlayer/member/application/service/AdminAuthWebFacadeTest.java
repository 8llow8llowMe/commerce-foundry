package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AdminLoginResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.AdminAuthWebPresenter;
import com.commercefoundry.domainlayer.member.application.command.AdminLoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.LoginAttemptLimitedException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AdminAccessTokenInfo;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditPort;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AuthTokenProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.LoginGuardProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberLoginProcessor;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminAuthWebFacadeTest {

    private static final Member ADMIN = Member.restore(9L, "admin@example.com", "$2a$10$admin", "관리자", null, MemberRole.ADMIN, 0L);
    private static final Member CUSTOMER = Member.restore(7L, "a@example.com", "$2a$10$hash", "홍길동", null, MemberRole.CUSTOMER, 0L);
    private static final AdminLoginCommand COMMAND = new AdminLoginCommand("Admin@Example.com", "admin-password-1");
    private static final String EMAIL = "admin@example.com";

    @Mock
    private MemberLoginProcessor memberLoginProcessor;

    @Mock
    private LoginGuardProcessor loginGuardProcessor;

    @Mock
    private AuthTokenProcessor authTokenProcessor;

    @Mock
    private AdminAuditPort adminAuditPort;

    private AdminAuthWebFacade facade;

    @BeforeEach
    void setUp() {
        facade = new AdminAuthWebFacade(memberLoginProcessor, loginGuardProcessor, authTokenProcessor, new AdminAuditProcessor(adminAuditPort),
            new AdminAuthWebPresenter());
    }

    @Test
    @DisplayName("정규화 → 관리자 시도 제한(ADMIN_LOGIN) → 조회 → 관리자 인증 → 창 지우기 → access 발급 순서이고, 성공을 감사 로그에 남긴다")
    void login() {
        given(memberLoginProcessor.normalizeEmail("Admin@Example.com")).willReturn(Optional.of(EMAIL));
        given(memberLoginProcessor.findByEmail(EMAIL)).willReturn(Optional.of(ADMIN));
        given(memberLoginProcessor.authenticateAdmin(Optional.of(ADMIN), "admin-password-1")).willReturn(ADMIN);
        given(authTokenProcessor.issueAdminAccessToken(ADMIN)).willReturn(new AdminAccessTokenInfo("access", 1800));

        AdminLoginResponse response = facade.login(COMMAND);

        assertThat(response).isEqualTo(AdminLoginResponse.builder().accessToken("access").accessTokenExpiresIn(1800).build());
        InOrder order = inOrder(memberLoginProcessor, loginGuardProcessor, authTokenProcessor, adminAuditPort);
        order.verify(loginGuardProcessor).admit(AttemptKind.ADMIN_LOGIN, EMAIL);
        order.verify(memberLoginProcessor).findByEmail(EMAIL);
        order.verify(memberLoginProcessor).authenticateAdmin(Optional.of(ADMIN), "admin-password-1");
        order.verify(loginGuardProcessor).clear(AttemptKind.ADMIN_LOGIN, EMAIL);
        order.verify(authTokenProcessor).issueAdminAccessToken(ADMIN);
        order.verify(adminAuditPort).adminLoginSucceeded(9L);
        verify(adminAuditPort, never()).adminLoginFailed(any());
        verify(loginGuardProcessor, never()).admit(AttemptKind.LOGIN, EMAIL);
    }

    @Test
    @DisplayName("관리자 계정을 노린 실패는 그 memberId 를 대상으로 감사하고 AUTH_030 을 그대로 던진다 — 창을 지우지 않고 토큰을 만들지 않는다")
    void failedOnAdminAccount() {
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.of(EMAIL));
        given(memberLoginProcessor.findByEmail(EMAIL)).willReturn(Optional.of(ADMIN));
        given(memberLoginProcessor.authenticateAdmin(Optional.of(ADMIN), "admin-password-1"))
            .willThrow(new MemberException(MemberErrorCode.ADMIN_LOGIN_FAILED));

        assertThatThrownBy(() -> facade.login(COMMAND))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.ADMIN_LOGIN_FAILED);
        verify(adminAuditPort).adminLoginFailed(OptionalLong.of(9L));
        verify(adminAuditPort, never()).adminLoginSucceeded(anyLong());
        verify(loginGuardProcessor, never()).clear(any(), any());
        verify(authTokenProcessor, never()).issueAdminAccessToken(any());
    }

    @Test
    @DisplayName("고객 계정 · 없는 이메일의 실패는 대상 없이 감사한다 — 고객 memberId 를 관리자 감사 로그에 남기지 않는다")
    void failedWithoutAdminTarget() {
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.of(EMAIL));
        given(memberLoginProcessor.findByEmail(EMAIL)).willReturn(Optional.of(CUSTOMER), Optional.empty());
        given(memberLoginProcessor.authenticateAdmin(any(), any())).willThrow(new MemberException(MemberErrorCode.ADMIN_LOGIN_FAILED));

        assertThatThrownBy(() -> facade.login(COMMAND)).isInstanceOf(MemberException.class);
        assertThatThrownBy(() -> facade.login(COMMAND)).isInstanceOf(MemberException.class);

        verify(adminAuditPort, times(2)).adminLoginFailed(OptionalLong.empty());
    }

    @Test
    @DisplayName("시도 초과면 조회 · BCrypt · 창 지우기 · 발급 없이 429 AUTH_040 을 던지고, 대상 없는 실패(target=-)로 감사한다")
    void limitedSkipsLookupAndAuditsWithoutTarget() {
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.of(EMAIL));
        LoginAttemptLimitedException limited = new LoginAttemptLimitedException(MemberErrorCode.LOGIN_ATTEMPT_LIMITED, Duration.ofMinutes(3));
        willThrow(limited).given(loginGuardProcessor).admit(AttemptKind.ADMIN_LOGIN, EMAIL);

        assertThatThrownBy(() -> facade.login(COMMAND)).isSameAs(limited);

        verify(memberLoginProcessor, never()).findByEmail(anyString());
        verify(memberLoginProcessor, never()).authenticateAdmin(any(), any());
        verify(loginGuardProcessor, never()).clear(any(), any());
        verifyNoInteractions(authTokenProcessor);
        verify(adminAuditPort).adminLoginFailed(OptionalLong.empty());
        verify(adminAuditPort, never()).adminLoginSucceeded(anyLong());
    }

    @Test
    @DisplayName("계수기 장애(503 AUTH_013)는 잡지 않는다(fail-closed) — 조회 · BCrypt · 감사 없이 그대로 올라간다")
    void counterUnavailableFailsClosed() {
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.of(EMAIL));
        willThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE)).given(loginGuardProcessor).admit(AttemptKind.ADMIN_LOGIN, EMAIL);

        assertThatThrownBy(() -> facade.login(COMMAND))
            .isInstanceOf(MemberException.class)
            .extracting(failure -> ((MemberException) failure).getErrorCode()).isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);

        verify(memberLoginProcessor, never()).findByEmail(anyString());
        verify(memberLoginProcessor, never()).authenticateAdmin(any(), any());
        verifyNoInteractions(authTokenProcessor, adminAuditPort);
    }

    @Test
    @DisplayName("정규화할 수 없는 이메일은 세지도 조회하지도 않고 없는 회원과 같은 더미 비교로 AUTH_030 — 대상 없이 감사한다")
    void unnormalizableEmailIsAbsentMember() {
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.empty());
        given(memberLoginProcessor.authenticateAdmin(Optional.empty(), "admin-password-1")).willThrow(new MemberException(MemberErrorCode.ADMIN_LOGIN_FAILED));

        assertThatThrownBy(() -> facade.login(COMMAND)).isInstanceOf(MemberException.class);

        verify(memberLoginProcessor, never()).findByEmail(anyString());
        verifyNoInteractions(loginGuardProcessor, authTokenProcessor);
        verify(adminAuditPort).adminLoginFailed(OptionalLong.empty());
    }
}
