package com.commercefoundry.domainlayer.member.adapter.in.web.presenter;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.MemberProfileResponse;
import com.commercefoundry.domainlayer.member.application.info.MemberProfileInfo;
import com.commercefoundry.domainlayer.member.application.info.MemberSignUpInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemberWebPresenterTest {

    private final MemberWebPresenter presenter = new MemberWebPresenter();

    @Test
    @DisplayName("가입 응답의 회원 아이디는 문자열이다 — 19자리 Snowflake 가 JS Number 에서 반올림되지 않게")
    void signUpIdIsString() {
        assertThat(presenter.toSignUpResponse(new MemberSignUpInfo(4611686018427387904L)).memberId()).isEqualTo("4611686018427387904");
    }

    @Test
    @DisplayName("내 정보는 아이디만 문자열로 바꾸고 나머지는 그대로 옮긴다 — 전화가 없으면 null")
    void profile() {
        MemberProfileResponse response = presenter.toProfileResponse(new MemberProfileInfo(4611686018427387905L, "a@example.com", "홍길동", null, true));

        assertThat(response).isEqualTo(MemberProfileResponse.builder()
            .memberId("4611686018427387905")
            .email("a@example.com")
            .name("홍길동")
            .phone(null)
            .marketingAgreed(true)
            .build());
    }
}
