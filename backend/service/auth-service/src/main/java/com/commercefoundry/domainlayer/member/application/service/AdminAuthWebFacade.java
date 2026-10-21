package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AdminLoginResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.AdminAuthWebPresenter;
import com.commercefoundry.domainlayer.member.application.command.AdminLoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.LoginAttemptLimitedException;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAuthWebUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AuthTokenProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.LoginGuardProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberLoginProcessor;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 관리자 로그인 (#6). 트랜잭션을 여기에 걸지 않는다 — 고객 로그인({@link AuthWebFacade})과 같은 이유로 비밀번호 비교(BCrypt)는 의도적으로 느린 CPU 작업이고
 * 시도 계수 저장소(Redis)는 외부 I/O 다. DB 구간(회원 조회)만 {@link MemberLoginProcessor} 에서 readOnly 로 건다. 토큰 발급은 서명뿐이다(refresh 없음).
 */
@Service
@RequiredArgsConstructor
public class AdminAuthWebFacade implements AdminAuthWebUseCase {

    private final MemberLoginProcessor memberLoginProcessor;
    private final LoginGuardProcessor loginGuardProcessor;
    private final AuthTokenProcessor authTokenProcessor;
    private final AdminAuditProcessor adminAuditProcessor;
    private final AdminAuthWebPresenter adminAuthWebPresenter;

    /**
     * 정규화(한 번) → 시도 제한 → 조회(readOnly) → 비밀번호 비교 · 관리자 확인(트랜잭션 밖) → 창 지우기 → access 발급. 순서 · fail-closed 는 고객 로그인과 같다
     * ({@code security-guide.md} §12). 창은 고객 로그인과 따로 센다({@link AttemptKind#ADMIN_LOGIN}) — 고객 로그인 경로로 관리자를 잠그지 못하게.
     *
     * <p>성공 · 실패를 감사 로그에 남긴다. 시도 제한으로 거절한 것도 실패로 남기되 대상은 {@code -} 다 — 거절은 DB 를 보지 않으므로(있는 계정 · 없는 계정이
     * 같은 시간) 노린 관리자 계정을 알 수 없다. 그 계정은 잠그기 전의 실패 줄들({@code target=member:<id>})이 이미 가리킨다.
     */
    @Override
    public AdminLoginResponse login(AdminLoginCommand command) {
        Optional<String> email = memberLoginProcessor.normalizeEmail(command.email());
        email.ifPresent(this::admit);
        Optional<Member> candidate = email.flatMap(memberLoginProcessor::findByEmail);
        Member admin;
        try {
            admin = memberLoginProcessor.authenticateAdmin(candidate, command.rawPassword());
        } catch (MemberException loginFailed) {
            adminAuditProcessor.adminLoginFailed(candidate);
            throw loginFailed;
        }
        email.ifPresent(normalized -> loginGuardProcessor.clear(AttemptKind.ADMIN_LOGIN, normalized));
        AdminLoginResponse response = adminAuthWebPresenter.toLoginResponse(authTokenProcessor.issueAdminAccessToken(admin));
        adminAuditProcessor.adminLoginSucceeded(admin.id());
        return response;
    }

    private void admit(String normalizedEmail) {
        try {
            loginGuardProcessor.admit(AttemptKind.ADMIN_LOGIN, normalizedEmail);
        } catch (LoginAttemptLimitedException limited) {
            adminAuditProcessor.adminLoginFailed(Optional.empty());
            throw limited;
        }
    }
}
