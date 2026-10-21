package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AdminLoginResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.AdminAuthWebPresenter;
import com.commercefoundry.domainlayer.member.application.command.AdminLoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AdminAccessTokenInfo;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditPort;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AuthTokenProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberLoginProcessor;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminAuthWebFacadeTest {

    private static final Member ADMIN = Member.restore(9L, "admin@example.com", "$2a$10$admin", "관리자", null, MemberRole.ADMIN, 0L);
    private static final Member CUSTOMER = Member.restore(7L, "a@example.com", "$2a$10$hash", "홍길동", null, MemberRole.CUSTOMER, 0L);
    private static final AdminLoginCommand COMMAND = new AdminLoginCommand("Admin@Example.com", "admin-password-1");

    @Mock
    private MemberLoginProcessor memberLoginProcessor;

    @Mock
    private AuthTokenProcessor authTokenProcessor;

    @Mock
    private AdminAuditPort adminAuditPort;

    private AdminAuthWebFacade facade;

    @BeforeEach
    void setUp() {
        facade = new AdminAuthWebFacade(memberLoginProcessor, authTokenProcessor, new AdminAuditProcessor(adminAuditPort), new AdminAuthWebPresenter());
    }

    @Test
    @DisplayName("관리자 인증을 지나면 access 만 내주고 성공을 감사 로그에 남긴다")
    void login() {
        given(memberLoginProcessor.findByEmail("Admin@Example.com")).willReturn(Optional.of(ADMIN));
        given(memberLoginProcessor.authenticateAdmin(Optional.of(ADMIN), "admin-password-1")).willReturn(ADMIN);
        given(authTokenProcessor.issueAdminAccessToken(ADMIN)).willReturn(new AdminAccessTokenInfo("access", 1800));

        AdminLoginResponse response = facade.login(COMMAND);

        assertThat(response).isEqualTo(AdminLoginResponse.builder().accessToken("access").accessTokenExpiresIn(1800).build());
        verify(adminAuditPort).adminLoginSucceeded(9L);
        verify(adminAuditPort, never()).adminLoginFailed(any());
    }

    @Test
    @DisplayName("관리자 계정을 노린 실패는 그 memberId 를 대상으로 감사하고 AUTH_030 을 그대로 던진다 — 토큰을 만들지 않는다")
    void failedOnAdminAccount() {
        given(memberLoginProcessor.findByEmail("Admin@Example.com")).willReturn(Optional.of(ADMIN));
        given(memberLoginProcessor.authenticateAdmin(Optional.of(ADMIN), "admin-password-1"))
            .willThrow(new MemberException(MemberErrorCode.ADMIN_LOGIN_FAILED));

        assertThatThrownBy(() -> facade.login(COMMAND))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.ADMIN_LOGIN_FAILED);
        verify(adminAuditPort).adminLoginFailed(OptionalLong.of(9L));
        verify(adminAuditPort, never()).adminLoginSucceeded(anyLong());
        verify(authTokenProcessor, never()).issueAdminAccessToken(any());
    }

    @Test
    @DisplayName("고객 계정 · 없는 이메일의 실패는 대상 없이 감사한다 — 고객 memberId 를 관리자 감사 로그에 남기지 않는다")
    void failedWithoutAdminTarget() {
        given(memberLoginProcessor.findByEmail("Admin@Example.com")).willReturn(Optional.of(CUSTOMER), Optional.empty());
        given(memberLoginProcessor.authenticateAdmin(any(), any())).willThrow(new MemberException(MemberErrorCode.ADMIN_LOGIN_FAILED));

        assertThatThrownBy(() -> facade.login(COMMAND)).isInstanceOf(MemberException.class);
        assertThatThrownBy(() -> facade.login(COMMAND)).isInstanceOf(MemberException.class);

        verify(adminAuditPort, org.mockito.Mockito.times(2)).adminLoginFailed(OptionalLong.empty());
    }
}
