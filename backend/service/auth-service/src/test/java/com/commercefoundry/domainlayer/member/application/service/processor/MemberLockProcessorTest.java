package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberLockProcessorTest {

    @Mock
    private MemberRepositoryPort memberRepositoryPort;

    @InjectMocks
    private MemberLockProcessor memberLockProcessor;

    @Test
    @DisplayName("회원 행을 잠금으로 읽으면 끝난다")
    void locks() {
        given(memberRepositoryPort.findByIdForUpdate(7L)).willReturn(Optional.of(Member.signUp(7L, "a@example.com", "$2a$10$hash", "홍길동", null)));

        assertThatCode(() -> memberLockProcessor.lock(7L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("회원이 없으면 AUTH_003 이다 — 토큰은 유효하지만 회원이 없다")
    void unknownMember() {
        given(memberRepositoryPort.findByIdForUpdate(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> memberLockProcessor.lock(7L))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.NOT_FOUND_MEMBER);
    }
}
