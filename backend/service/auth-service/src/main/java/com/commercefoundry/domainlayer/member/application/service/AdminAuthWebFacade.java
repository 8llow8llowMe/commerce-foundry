package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AdminLoginResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.AdminAuthWebPresenter;
import com.commercefoundry.domainlayer.member.application.command.AdminLoginCommand;
import com.commercefoundry.domainlayer.member.application.command.AdminOtpLoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.LoginAttemptLimitedException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AdminLoginChallengeInfo;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpChallenge;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpProof;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAuthWebUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccessCutoffProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminLoginChallengeProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminOtpProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AuthTokenProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.LoginGuardProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberLoginProcessor;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * 관리자 로그인 (#6 · #238 PR-B). 트랜잭션을 여기에 걸지 않는다 — 고객 로그인({@link AuthWebFacade})과 같은 이유로 비밀번호 · 복구 코드 비교(BCrypt)는
 * 의도적으로 느린 CPU 작업이고 시도 계수 · 챌린지 · 컷오프 저장소(Redis)는 외부 I/O 다. DB 구간(회원 · OTP 조회)은 각 Processor 에서 readOnly 로, 코드 소비만
 * {@link AdminOtpProcessor#consumeForLogin} 의 짧은 쓰기 트랜잭션으로 건다. 토큰 발급은 서명뿐이다(refresh 없음).
 *
 * <p>OTP 를 등록한 관리자(#238)는 비밀번호만으로 토큰을 받지 못한다 — 1단계는 챌린지만 주고 2단계({@link #loginWithOtp})가 코드를 확인한 뒤에 토큰을 준다.
 */
@Service
@RequiredArgsConstructor
public class AdminAuthWebFacade implements AdminAuthWebUseCase {

    private final MemberLoginProcessor memberLoginProcessor;
    private final LoginGuardProcessor loginGuardProcessor;
    private final AuthTokenProcessor authTokenProcessor;
    private final AdminAuditProcessor adminAuditProcessor;
    private final AdminAccountProcessor adminAccountProcessor;
    private final AdminOtpProcessor adminOtpProcessor;
    private final AdminLoginChallengeProcessor adminLoginChallengeProcessor;
    private final AdminAccessCutoffProcessor adminAccessCutoffProcessor;
    private final AdminAuthWebPresenter adminAuthWebPresenter;
    private final Clock clock;

    /**
     * 정규화(한 번) → 시도 제한 → 조회(readOnly) → 비밀번호 비교 · 관리자 확인(트랜잭션 밖) → OTP 등록 여부 → access 발급 또는 챌린지. 순서 · fail-closed 는
     * 고객 로그인과 같다({@code security-guide.md} §14). 창은 고객 로그인과 따로 센다({@link AttemptKind#ADMIN_LOGIN}) — 고객 로그인 경로로 관리자를 잠그지 못하게.
     *
     * <p><b>성공해도 창을 지우지 않는다</b>(고객 로그인과 다르다, 사용자 결정 2026-10-22). 성공 시 DEL 이 있으면 같은 이메일로 시도를 쌓던 쪽이 카운터가
     * 0 으로 돌아간 것(429 가 늦게 오는 것)으로 그 이메일이 관리자 계정이고 언제 로그인했는지를 알아낸다 — 보안을 우선한다. 대가로 관리자도 창 안에서
     * 허용 횟수를 넘겨 시도하면(성공 포함) 창이 끝날 때까지 429 다. 1인 운영이라 감수하고, 비상 시 키를 직접 지운다({@code security-guide.md} §14-4).
     *
     * <p>OTP 를 등록한 관리자는 비밀번호가 맞아도 access 를 받지 않고 2단계 챌린지만 받는다(#238 PR-B). 챌린지에는 발급 시각을 같이 둔다 — 2단계가 비밀번호 변경 ·
     * 비활성화 컷오프와 비교한다. 로그인 성공 감사는 2단계 성공 때 남기고, 여기서는 챌린지를 둔 뒤 1단계 통과({@code ADMIN_LOGIN_PASSWORD_VERIFIED}, 대상
     * {@code member:<id>})를 남긴다 — 비밀번호가 새어 2단계를 대입하는 흐름을 감사 로그로 찾을 수 있게. 챌린지 저장소 장애는 503 {@code AUTH_013}(fail-closed)이다.
     *
     * <p>성공 · 실패를 감사 로그에 남긴다. 시도 제한으로 거절한 것도 실패로 남기되 대상은 {@code -} 다 — 거절은 DB 를 보지 않으므로(있는 계정 · 없는 계정이
     * 같은 시간) 노린 관리자 계정을 알 수 없다. 그 계정은 잠그기 전의 실패 줄들({@code target=member:<id>})이 이미 가리킨다.
     */
    @Override
    public AdminLoginResponse login(AdminLoginCommand command) {
        // 비밀번호 조회 전에 붙인 시각이어야 조회·비교 중 비밀번호 변경이 완료돼도 옛 자격으로 받은 챌린지가 컷오프보다 앞선다.
        Instant loginStartedAt = clock.instant();
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
        // 창을 지우지 않는다(clear 없음) — 성공 DEL 이 관리자 여부 · 로그인 시점의 오라클이 된다 (메서드 설명)
        if (adminOtpProcessor.isEnrolled(admin.id())) {
            AdminLoginChallengeInfo challenge = adminLoginChallengeProcessor.issue(admin.id(), loginStartedAt);
            adminAuditProcessor.adminLoginPasswordVerified(admin.id());
            return adminAuthWebPresenter.toOtpRequiredResponse(challenge);
        }
        AdminLoginResponse response = adminAuthWebPresenter.toLoginResponse(authTokenProcessor.issueAdminAccessToken(admin));
        adminAuditProcessor.adminLoginSucceeded(admin.id());
        return response;
    }

    /**
     * 챌린지 조회(소비하지 않음) → 2단계 시도 제한 → 활성 관리자 재확인 → 컷오프 확인 → 코드 확인(트랜잭션 밖) → 챌린지 소비(GETDEL) → 코드 소비(자격 잠금 트랜잭션)
     * → access 발급.
     *
     * <ul>
     *   <li>시도 제한은 챌린지로 관리자를 안 뒤 코드를 보기 전에 1회 센다({@link AttemptKind#ADMIN_OTP_LOGIN} — 재인증 창과 따로, 성공해도 지우지 않는다).
     *       챌린지는 실패해도 TTL 까지 다시 쓸 수 있으므로(오타마다 비밀번호를 다시 받지 않는다) 6자리 대입을 막는 것은 이 창이다</li>
     *   <li>그 사이 비활성화된 관리자 · OTP 를 해제한 관리자 · <b>비밀번호를 바꾼 관리자</b>(챌린지 발급 시각이 컷오프 이전)는 챌린지 무효({@code AUTH_034})로 답한다 —
     *       이유를 나누지 않는다. 컷오프를 읽지 못하면 503 {@code AUTH_013}(fail-closed)이다</li>
     *   <li>챌린지 소비를 코드 소비보다 먼저 한다 — 토큰 없이 코드만 소비되는 경로를 없앤다. 같은 챌린지의 동시 2단계는 GETDEL 이 한쪽만 통과시키고, 진 쪽은 코드를
     *       소비하지 않는다(복구 코드가 남는다). 만료 · 저장소 장애로 챌린지를 지우지 못해도 코드는 남는다. 대가로 챌린지를 지운 뒤 코드 소비가 실패하면
     *       ({@code AUTH_035} 다른 요청이 같은 코드를 먼저 씀 · {@code AUTH_044} 잠금 대기 초과) 챌린지가 이미 없어 1단계부터 다시 한다</li>
     *   <li>챌린지와 2단계 access 의 {@code iat} 는 1단계 조회 전 시각으로 묶는다. 그 뒤 비밀번호 변경·비활성화 컷오프가 기록되면 발급이 다음 초로 넘어가도
     *       게이트웨이가 토큰을 끊는다</li>
     * </ul>
     *
     * <p>감사 — 판정 전 실패(챌린지 무효로 대상을 모름 · 시도 제한 429 · 계수기 장애 503)는 남기지 않는다(A2 와 같은 원칙). 대상을 안 뒤의 실패는 예상 밖 예외까지
     * {@code ADMIN_LOGIN} 실패(대상 {@code member:<id>})로 남기고 다시 던진다. 성공은 {@code ADMIN_LOGIN} 성공이다. 코드가 TOTP 였는지 복구 코드였는지는 감사 · 응답에 싣지 않는다.
     */
    @Override
    public AdminLoginResponse loginWithOtp(AdminOtpLoginCommand command) {
        AdminOtpChallenge challenge = adminLoginChallengeProcessor.find(command.challengeToken());
        long adminMemberId = challenge.memberId();
        loginGuardProcessor.admitAdminOtpLogin(adminMemberId);
        AdminLoginResponse response;
        try {
            Member admin = asChallengeFailure(() -> adminAccountProcessor.findActiveAdmin(adminMemberId));
            rejectIfCutOff(challenge);
            AdminOtpProof proof = asChallengeFailure(() -> adminOtpProcessor.resolveProof(adminMemberId, command.code(), clock.instant()));
            adminLoginChallengeProcessor.consume(command.challengeToken(), challenge);
            asChallengeFailure(() -> inOtpQueue(() -> adminOtpProcessor.consumeForLogin(adminMemberId, proof, clock.instant())));
            response = adminAuthWebPresenter.toLoginResponse(authTokenProcessor.issueAdminAccessToken(admin, challenge.issuedAt()));
        } catch (RuntimeException failure) {
            // MemberException 이 아닌 예상 밖 예외(버그 · DB 오류)도 대상을 안 뒤의 실패라 남긴다 — 응답은 전역 핸들러가 그대로 만든다
            adminAuditProcessor.adminLoginSecondFactorFailed(adminMemberId);
            throw failure;
        }
        adminAuditProcessor.adminLoginSucceeded(adminMemberId);
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

    /** 비밀번호 변경 · 비활성화 컷오프 이전에 받은 챌린지는 무효 {@code AUTH_034} 다. 비교 규칙은 {@link AdminAccessCutoffProcessor#issuedBeforeCutoff}. */
    private void rejectIfCutOff(AdminOtpChallenge challenge) {
        if (adminAccessCutoffProcessor.issuedBeforeCutoff(challenge.memberId(), challenge.issuedAt())) {
            throw new MemberException(MemberErrorCode.OTP_CHALLENGE_INVALID);
        }
    }

    /**
     * 챌린지를 받은 뒤 관리자가 비활성화됐거나({@code AUTH_033}) OTP 를 해제했으면({@code AUTH_036} — 확인 · 소비 어느 쪽에서 알아도) 챌린지 무효 {@code AUTH_034} 로
     * 답한다 — 이유를 나누지 않는다.
     */
    private static <T> T asChallengeFailure(Supplier<T> work) {
        try {
            return work.get();
        } catch (MemberException failure) {
            if (failure.getErrorCode() == MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND || failure.getErrorCode() == MemberErrorCode.OTP_NOT_ENROLLED) {
                throw new MemberException(MemberErrorCode.OTP_CHALLENGE_INVALID, failure);
            }
            throw failure;
        }
    }

    /**
     * 코드 소비 트랜잭션을 부른다. 잠금 실패 · 시간 상한 초과는 409 {@code AUTH_044} 다 — 재인증과 같은 변환을 복제했다(잡는 예외를 바꾸면 함께 바꾼다).
     * 트랜잭션은 예외로 롤백된다. 로그인 2단계에서는 챌린지를 이미 지운 뒤라 {@code AUTH_044} 면 1단계부터 다시 한다.
     *
     * @see AdminOtpFacade#inOtpQueue
     */
    private static Void inOtpQueue(Runnable work) {
        try {
            work.run();
            return null;
        } catch (QueryTimeoutException | TransactionTimedOutException | PessimisticLockingFailureException exception) {
            throw new MemberException(MemberErrorCode.OTP_BUSY, exception);
        }
    }
}
