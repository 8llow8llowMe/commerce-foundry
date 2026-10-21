package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AuthTokenResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.AuthWebPresenter;
import com.commercefoundry.domainlayer.member.application.command.LoginCommand;
import com.commercefoundry.domainlayer.member.application.port.in.AuthWebUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AuthTokenProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.LoginGuardProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberLoginProcessor;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 트랜잭션을 여기에 걸지 않는다 — 토큰 · 시도 계수 저장소(Redis)는 외부 I/O 이고 비밀번호 비교(BCrypt)는 의도적으로 느린 CPU 작업이다.
 * 트랜잭션 안에서 하면 그동안 DB 커넥션을 쥐고 있게 된다. DB 구간(회원 조회)만 {@link MemberLoginProcessor} 에서 readOnly 로 건다
 * ({@code architecture-guide.md} §3 Facade).
 */
@Service
@RequiredArgsConstructor
public class AuthWebFacade implements AuthWebUseCase {

    private final MemberLoginProcessor memberLoginProcessor;
    private final LoginGuardProcessor loginGuardProcessor;
    private final AuthTokenProcessor authTokenProcessor;
    private final AuthWebPresenter authWebPresenter;

    /**
     * 정규화(한 번) → 시도 제한 → 조회(readOnly) → 비밀번호 비교(트랜잭션 밖) → 창 지우기 → refresh 저장 · access 발급 (#66, {@code security-guide.md} §12).
     *
     * <ul>
     *   <li>시도 제한을 조회 · BCrypt 보다 먼저 본다 — 초과면 DB · BCrypt · 발급 없이 429 {@code AUTH_040} 이라, 있는 계정과 없는 계정이 같은 응답 · 같은 시간이다.</li>
     *   <li>계수기 장애(503 {@code AUTH_013})는 잡지 않는다(fail-closed) — BCrypt 도 돌지 않는다.</li>
     *   <li>정규화할 수 없는 이메일은 세지 않고 조회도 하지 않는다 — "없는 회원" 과 같은 더미 비교로 400 {@code AUTH_001} 이다. 어떤 계정과도 맞지 않는다.</li>
     *   <li>창은 인증에 성공했을 때만 지운다(best-effort). 인증에 성공했으면 이메일은 언제나 있다.</li>
     * </ul>
     */
    @Override
    public AuthTokenResponse login(LoginCommand command) {
        Optional<String> email = memberLoginProcessor.normalizeEmail(command.email());
        email.ifPresent(normalized -> loginGuardProcessor.admit(AttemptKind.LOGIN, normalized));
        Optional<Member> candidate = email.flatMap(memberLoginProcessor::findByEmail);
        Member member = memberLoginProcessor.authenticate(candidate, command.rawPassword());
        email.ifPresent(normalized -> loginGuardProcessor.clear(AttemptKind.LOGIN, normalized));
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
