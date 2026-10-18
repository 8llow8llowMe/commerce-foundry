package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.MemberProfileResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.SignUpResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.MemberWebPresenter;
import com.commercefoundry.domainlayer.member.application.command.MemberProfileUpdateCommand;
import com.commercefoundry.domainlayer.member.application.command.SignUpCommand;
import com.commercefoundry.domainlayer.member.application.port.in.MemberWebUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberProfileProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberSignUpProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberWebFacade implements MemberWebUseCase {

    private final MemberSignUpProcessor memberSignUpProcessor;
    private final MemberProfileProcessor memberProfileProcessor;
    private final MemberWebPresenter memberWebPresenter;

    /**
     * 트랜잭션을 여기에 걸지 않고 {@link MemberSignUpProcessor#register} 로 좁힌다 — 비밀번호 해시(BCrypt)는 의도적으로 느린 작업이라
     * 트랜잭션 안에서 하면 그동안 DB 커넥션을 쥐고 있게 된다. 해시는 저장할 값을 만들 뿐이라 트랜잭션 밖이어도 정합성이 깨지지 않는다.
     * 해시를 중복 확인보다 먼저 하는 것도 의도다 — 이미 가입된 이메일이 해시를 건너뛰어 빨리 끝나면 응답 시간으로 계정 존재가 드러난다.
     */
    @Override
    public SignUpResponse signUp(SignUpCommand command) {
        String passwordHash = memberSignUpProcessor.hashPassword(command.rawPassword());
        return memberWebPresenter.toSignUpResponse(memberSignUpProcessor.register(command, passwordHash));
    }

    @Override
    @Transactional(readOnly = true)
    public MemberProfileResponse getMyProfile(long memberId) {
        return memberWebPresenter.toProfileResponse(memberProfileProcessor.getProfile(memberId));
    }

    @Override
    @Transactional
    public MemberProfileResponse updateMyProfile(long memberId, MemberProfileUpdateCommand command) {
        return memberWebPresenter.toProfileResponse(memberProfileProcessor.updateProfile(memberId, command));
    }
}
