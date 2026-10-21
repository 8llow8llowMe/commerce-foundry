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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AuthTokenResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.AuthWebPresenter;
import com.commercefoundry.domainlayer.member.application.command.LoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.LoginAttemptLimitedException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AuthTokenInfo;
import com.commercefoundry.domainlayer.member.application.service.processor.AuthTokenProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.LoginGuardProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberLoginProcessor;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import com.commercefoundry.security.principal.MemberRole;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthWebFacadeTest {

    private static final Member MEMBER = Member.restore(7L, "a@example.com", "$2a$10$hash", "홍길동", null, MemberRole.CUSTOMER, 0L);
    private static final AuthTokenInfo TOKENS = new AuthTokenInfo("access", 1800, "refresh", 1209600);
    private static final RefreshTokenSession SESSION = new RefreshTokenSession("fingerprint-0123456789", 7L, "family");
    private static final LoginCommand COMMAND = new LoginCommand("  A@Example.com ", "pw");

    @Mock
    private MemberLoginProcessor memberLoginProcessor;

    @Mock
    private LoginGuardProcessor loginGuardProcessor;

    @Mock
    private AuthTokenProcessor authTokenProcessor;

    private AuthWebFacade facade;

    @BeforeEach
    void setUp() {
        facade = new AuthWebFacade(memberLoginProcessor, loginGuardProcessor, authTokenProcessor, new AuthWebPresenter());
    }

    @Test
    @DisplayName("로그인은 정규화 → 시도 제한 → 조회 → 비밀번호 확인 → 창 지우기 → 토큰 발급 순서이고, 한 번 정규화한 같은 값을 넘긴다")
    void login() {
        given(memberLoginProcessor.normalizeEmail("  A@Example.com ")).willReturn(Optional.of("a@example.com"));
        given(memberLoginProcessor.findByEmail("a@example.com")).willReturn(Optional.of(MEMBER));
        given(memberLoginProcessor.authenticate(Optional.of(MEMBER), "pw")).willReturn(MEMBER);
        given(authTokenProcessor.issueForLogin(MEMBER)).willReturn(TOKENS);

        AuthTokenResponse response = facade.login(COMMAND);

        assertThat(response).isEqualTo(new AuthTokenResponse("access", 1800, "refresh", 1209600));
        assertThat(response.toString()).doesNotContain("=access", "=refresh");
        InOrder order = inOrder(memberLoginProcessor, loginGuardProcessor, authTokenProcessor);
        order.verify(memberLoginProcessor).normalizeEmail("  A@Example.com ");
        order.verify(loginGuardProcessor).admit(AttemptKind.LOGIN, "a@example.com");
        order.verify(memberLoginProcessor).findByEmail("a@example.com");
        order.verify(memberLoginProcessor).authenticate(Optional.of(MEMBER), "pw");
        order.verify(loginGuardProcessor).clear(AttemptKind.LOGIN, "a@example.com");
        order.verify(authTokenProcessor).issueForLogin(MEMBER);
    }

    @Test
    @DisplayName("로그인 실패면 창을 지우지 않고 토큰을 발급하지 않는다 — 실패는 그 창에 남는다")
    void loginFailedIssuesNothing() {
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.of("a@example.com"));
        given(memberLoginProcessor.findByEmail("a@example.com")).willReturn(Optional.empty());
        given(memberLoginProcessor.authenticate(Optional.empty(), "pw")).willThrow(new MemberException(MemberErrorCode.LOGIN_FAILED));

        assertThatThrownBy(() -> facade.login(COMMAND)).isInstanceOf(MemberException.class);

        verify(loginGuardProcessor).admit(AttemptKind.LOGIN, "a@example.com");
        verify(loginGuardProcessor, never()).clear(any(), any());
        verify(authTokenProcessor, never()).issueForLogin(any());
    }

    @Test
    @DisplayName("시도 초과면 조회 · 비밀번호 비교(BCrypt) · 창 지우기 · 발급을 하지 않는다 — 있는 계정 · 없는 계정이 같은 응답 · 같은 시간이다")
    void limitedSkipsLookupAndBcrypt() {
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.of("a@example.com"));
        LoginAttemptLimitedException limited = new LoginAttemptLimitedException(MemberErrorCode.LOGIN_ATTEMPT_LIMITED, Duration.ofMinutes(3));
        willThrow(limited).given(loginGuardProcessor).admit(AttemptKind.LOGIN, "a@example.com");

        assertThatThrownBy(() -> facade.login(COMMAND)).isSameAs(limited);

        verify(memberLoginProcessor, never()).findByEmail(anyString());
        verify(memberLoginProcessor, never()).authenticate(any(), any());
        verify(loginGuardProcessor, never()).clear(any(), any());
        verifyNoInteractions(authTokenProcessor);
    }

    @Test
    @DisplayName("계수기 장애(503 AUTH_013)는 잡지 않는다(fail-closed) — 조회 · BCrypt 없이 그대로 올라간다")
    void counterUnavailableFailsClosed() {
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.of("a@example.com"));
        willThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE)).given(loginGuardProcessor).admit(AttemptKind.LOGIN, "a@example.com");

        assertThatThrownBy(() -> facade.login(COMMAND))
            .isInstanceOf(MemberException.class)
            .extracting(failure -> ((MemberException) failure).getErrorCode()).isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);

        verify(memberLoginProcessor, never()).findByEmail(anyString());
        verify(memberLoginProcessor, never()).authenticate(any(), any());
        verifyNoInteractions(authTokenProcessor);
    }

    @Test
    @DisplayName("정규화할 수 없는 이메일은 세지도 조회하지도 않고, 없는 회원과 같은 더미 비교로 AUTH_001 이다")
    void unnormalizableEmailIsAbsentMember() {
        given(memberLoginProcessor.normalizeEmail("　")).willReturn(Optional.empty());
        given(memberLoginProcessor.authenticate(Optional.empty(), "pw")).willThrow(new MemberException(MemberErrorCode.LOGIN_FAILED));

        assertThatThrownBy(() -> facade.login(new LoginCommand("　", "pw")))
            .isInstanceOf(MemberException.class)
            .extracting(failure -> ((MemberException) failure).getErrorCode()).isEqualTo(MemberErrorCode.LOGIN_FAILED);

        verify(memberLoginProcessor).authenticate(Optional.empty(), "pw");
        verify(memberLoginProcessor, never()).findByEmail(anyString());
        verifyNoInteractions(loginGuardProcessor, authTokenProcessor);
    }

    @Test
    @DisplayName("재발급은 세션 읽기 → 회원 조회 → 회전 순서다 — 회원 조회가 회전보다 먼저이고 시도 제한을 보지 않는다")
    void reissueReadsMemberBeforeRotation() {
        given(authTokenProcessor.findSession("refresh-token")).willReturn(SESSION);
        given(memberLoginProcessor.findCustomer(7L)).willReturn(Optional.of(MEMBER));
        given(authTokenProcessor.reissue(SESSION, MEMBER)).willReturn(TOKENS);

        assertThat(facade.reissue("refresh-token").refreshToken()).isEqualTo("refresh");

        InOrder order = inOrder(authTokenProcessor, memberLoginProcessor);
        order.verify(authTokenProcessor).findSession("refresh-token");
        order.verify(memberLoginProcessor).findCustomer(7L);
        order.verify(authTokenProcessor).reissue(SESSION, MEMBER);
        verifyNoInteractions(loginGuardProcessor);
    }

    @Test
    @DisplayName("세션이 없으면 회원을 조회하지 않는다")
    void reissueStopsAtInvalidSession() {
        given(authTokenProcessor.findSession("refresh-token")).willThrow(new MemberException(MemberErrorCode.REFRESH_TOKEN_INVALID));

        assertThatThrownBy(() -> facade.reissue("refresh-token")).isInstanceOf(MemberException.class);
        verify(memberLoginProcessor, never()).findCustomer(anyLong());
    }

    @Test
    @DisplayName("로그아웃은 memberId · jti · refresh 토큰을 그대로 넘긴다")
    void logout() {
        facade.logout(7L, "jti", "refresh");

        verify(authTokenProcessor).logout(7L, "jti", "refresh");
    }
}
