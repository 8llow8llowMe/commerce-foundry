package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.MemberProfileResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.SignUpResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.MemberWebPresenter;
import com.commercefoundry.domainlayer.member.application.command.MemberProfileUpdateCommand;
import com.commercefoundry.domainlayer.member.application.command.SignUpCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.MemberProfileInfo;
import com.commercefoundry.domainlayer.member.application.info.MemberSignUpInfo;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberProfileProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberSignUpProcessor;
import com.commercefoundry.domainlayer.member.domain.model.SignUpAgreements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberWebFacadeTest {

    private static final SignUpCommand COMMAND = new SignUpCommand("a@example.com", "mokoyuri2026!", "홍길동", null, new SignUpAgreements(true, true, false));

    @Mock
    private MemberSignUpProcessor memberSignUpProcessor;

    @Mock
    private MemberProfileProcessor memberProfileProcessor;

    private MemberWebFacade facade;

    @BeforeEach
    void setUp() {
        facade = new MemberWebFacade(memberSignUpProcessor, memberProfileProcessor, new MemberWebPresenter());
    }

    @Test
    @DisplayName("가입은 해시를 먼저 만들고(트랜잭션 밖) 그 해시로 저장한다 — 응답 아이디는 문자열이다")
    void signUpHashesBeforeRegister() {
        given(memberSignUpProcessor.hashPassword("mokoyuri2026!")).willReturn("$2a$10$hash");
        given(memberSignUpProcessor.register(COMMAND, "$2a$10$hash")).willReturn(new MemberSignUpInfo(4611686018427387904L));

        SignUpResponse response = facade.signUp(COMMAND);

        assertThat(response.memberId()).isEqualTo("4611686018427387904");
        InOrder order = inOrder(memberSignUpProcessor);
        order.verify(memberSignUpProcessor).hashPassword("mokoyuri2026!");
        order.verify(memberSignUpProcessor).register(COMMAND, "$2a$10$hash");
    }

    @Test
    @DisplayName("중복 이메일 예외는 그대로 올린다")
    void signUpDuplicate() {
        given(memberSignUpProcessor.hashPassword(any())).willReturn("$2a$10$hash");
        given(memberSignUpProcessor.register(any(), any())).willThrow(new MemberException(MemberErrorCode.DUPLICATE_EMAIL));

        assertThatThrownBy(() -> facade.signUp(COMMAND)).isInstanceOf(MemberException.class);
        verifyNoInteractions(memberProfileProcessor);
    }

    @Test
    @DisplayName("내 정보 조회 · 수정은 토큰의 memberId 를 그대로 넘기고 Presenter 로 응답을 만든다")
    void profile() {
        MemberProfileInfo info = new MemberProfileInfo(7L, "a@example.com", "홍길동", null, true);
        MemberProfileUpdateCommand command = new MemberProfileUpdateCommand("홍길동", null, null);
        given(memberProfileProcessor.getProfile(7L)).willReturn(info);
        given(memberProfileProcessor.updateProfile(7L, command)).willReturn(info);

        MemberProfileResponse expected = MemberProfileResponse.builder().memberId("7").email("a@example.com").name("홍길동").marketingAgreed(true).build();
        assertThat(facade.getMyProfile(7L)).isEqualTo(expected);
        assertThat(facade.updateMyProfile(7L, command)).isEqualTo(expected);
        verify(memberProfileProcessor).updateProfile(7L, command);
    }
}
