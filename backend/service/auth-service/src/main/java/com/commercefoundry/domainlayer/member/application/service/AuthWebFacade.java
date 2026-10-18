package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AuthTokenResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.AuthWebPresenter;
import com.commercefoundry.domainlayer.member.application.command.LoginCommand;
import com.commercefoundry.domainlayer.member.application.port.in.AuthWebUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AuthTokenProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberLoginProcessor;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 트랜잭션을 여기에 걸지 않는다 — 토큰 저장소(Redis)는 외부 I/O 이고 비밀번호 비교(BCrypt)는 의도적으로 느린 CPU 작업이다.
 * 트랜잭션 안에서 하면 그동안 DB 커넥션을 쥐고 있게 된다. DB 구간(회원 조회)만 {@link MemberLoginProcessor} 에서 readOnly 로 건다
 * ({@code architecture-guide.md} §3 Facade).
 */
@Service
@RequiredArgsConstructor
public class AuthWebFacade implements AuthWebUseCase {

    private final MemberLoginProcessor memberLoginProcessor;
    private final AuthTokenProcessor authTokenProcessor;
    private final AuthWebPresenter authWebPresenter;

    /** 조회(readOnly) → 비밀번호 비교(트랜잭션 밖) → refresh 저장 · access 발급. */
    @Override
    public AuthTokenResponse login(LoginCommand command) {
        Optional<Member> candidate = memberLoginProcessor.findByEmail(command.email());
        Member member = memberLoginProcessor.authenticate(candidate, command.rawPassword());
        return authWebPresenter.toTokenResponse(authTokenProcessor.issueForLogin(member));
    }

    /**
     * 세션 읽기 → 회원 조회(역할) → 회전 → 발급 순서다. 회원 조회를 회전보다 먼저 한다 — 회전 뒤에 DB 가 실패하면
     * 옛 토큰은 회전됐는데 새 토큰은 받지 못한 상태가 남는다(유예가 지나면 재시도가 재사용 감지로 끝난다).
     */
    @Override
    public AuthTokenResponse reissue(String refreshToken) {
        RefreshTokenSession session = authTokenProcessor.findSession(refreshToken);
        Member customer = memberLoginProcessor.findCustomer(session.memberId()).orElse(null);
        return authWebPresenter.toTokenResponse(authTokenProcessor.reissue(session, customer));
    }

    @Override
    public void logout(long memberId, String tokenId, String refreshToken) {
        authTokenProcessor.logout(memberId, tokenId, refreshToken);
    }
}
