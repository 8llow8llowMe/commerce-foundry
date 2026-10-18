package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AuthTokenResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.AuthWebPresenter;
import com.commercefoundry.domainlayer.member.application.command.LoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AuthTokenInfo;
import com.commercefoundry.domainlayer.member.application.service.processor.AuthTokenProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberLoginProcessor;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import com.commercefoundry.security.principal.MemberRole;
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

    @Mock
    private MemberLoginProcessor memberLoginProcessor;

    @Mock
    private AuthTokenProcessor authTokenProcessor;

    private AuthWebFacade facade;

    @BeforeEach
    void setUp() {
        facade = new AuthWebFacade(memberLoginProcessor, authTokenProcessor, new AuthWebPresenter());
    }

    @Test
    @DisplayName("로그인은 조회 → 비밀번호 확인 → 토큰 발급 순서이고 토큰 4칸을 그대로 옮긴다")
    void login() {
        given(memberLoginProcessor.findByEmail("a@example.com")).willReturn(Optional.of(MEMBER));
        given(memberLoginProcessor.authenticate(Optional.of(MEMBER), "pw")).willReturn(MEMBER);
        given(authTokenProcessor.issueForLogin(MEMBER)).willReturn(TOKENS);

        AuthTokenResponse response = facade.login(new LoginCommand("a@example.com", "pw"));

        assertThat(response).isEqualTo(new AuthTokenResponse("access", 1800, "refresh", 1209600));
        assertThat(response.toString()).doesNotContain("=access", "=refresh");
    }

    @Test
    @DisplayName("로그인 실패면 토큰을 발급하지 않는다")
    void loginFailedIssuesNothing() {
        given(memberLoginProcessor.findByEmail("a@example.com")).willReturn(Optional.empty());
        given(memberLoginProcessor.authenticate(Optional.empty(), "pw")).willThrow(new MemberException(MemberErrorCode.LOGIN_FAILED));

        assertThatThrownBy(() -> facade.login(new LoginCommand("a@example.com", "pw"))).isInstanceOf(MemberException.class);
        verify(authTokenProcessor, never()).issueForLogin(any());
    }

    @Test
    @DisplayName("재발급은 세션 읽기 → 회원 조회 → 회전 순서다 — 회원 조회가 회전보다 먼저다")
    void reissueReadsMemberBeforeRotation() {
        given(authTokenProcessor.findSession("refresh-token")).willReturn(SESSION);
        given(memberLoginProcessor.findCustomer(7L)).willReturn(Optional.of(MEMBER));
        given(authTokenProcessor.reissue(SESSION, MEMBER)).willReturn(TOKENS);

        assertThat(facade.reissue("refresh-token").refreshToken()).isEqualTo("refresh");

        InOrder order = inOrder(authTokenProcessor, memberLoginProcessor);
        order.verify(authTokenProcessor).findSession("refresh-token");
        order.verify(memberLoginProcessor).findCustomer(7L);
        order.verify(authTokenProcessor).reissue(SESSION, MEMBER);
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
