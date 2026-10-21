package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AdminLoginResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.AdminAuthWebPresenter;
import com.commercefoundry.domainlayer.member.application.command.AdminLoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAuthWebUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AuthTokenProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberLoginProcessor;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 관리자 로그인 (#6). 트랜잭션을 여기에 걸지 않는다 — 고객 로그인({@link AuthWebFacade})과 같은 이유로 비밀번호 비교(BCrypt)는 의도적으로 느린 CPU 작업이다.
 * DB 구간(회원 조회)만 {@link MemberLoginProcessor} 에서 readOnly 로 건다. 토큰 발급은 서명뿐이라 저장소를 부르지 않는다(refresh 없음).
 */
@Service
@RequiredArgsConstructor
public class AdminAuthWebFacade implements AdminAuthWebUseCase {

    private final MemberLoginProcessor memberLoginProcessor;
    private final AuthTokenProcessor authTokenProcessor;
    private final AdminAuditProcessor adminAuditProcessor;
    private final AdminAuthWebPresenter adminAuthWebPresenter;

    /** 조회(readOnly) → 비밀번호 비교 · 관리자 확인(트랜잭션 밖) → access 발급. 성공 · 실패를 감사 로그에 남긴다. */
    @Override
    public AdminLoginResponse login(AdminLoginCommand command) {
        Optional<Member> candidate = memberLoginProcessor.findByEmail(command.email());
        Member admin;
        try {
            admin = memberLoginProcessor.authenticateAdmin(candidate, command.rawPassword());
        } catch (MemberException loginFailed) {
            adminAuditProcessor.adminLoginFailed(candidate);
            throw loginFailed;
        }
        AdminLoginResponse response = adminAuthWebPresenter.toLoginResponse(authTokenProcessor.issueAdminAccessToken(admin));
        adminAuditProcessor.adminLoginSucceeded(admin.id());
        return response;
    }
}
