package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.LoginAttemptLimitedException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.LoginAttemptCounterPort;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptOutcome;
import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptPolicy;
import com.commercefoundry.domainlayer.member.domain.model.LoginGuardPolicy;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 로그인 · 관리자 로그인 · 회원가입 시도 제한 (#66, {@code security-guide.md} §14). Facade 는 계수기 포트를 직접 부르지 않고 이 Processor 를 거친다 (ArchUnit H11).
 *
 * <ul>
 *   <li>{@link #admit} — 시도 1회를 먼저 센 뒤(인증 전) 허용 횟수를 넘겼으면 {@link LoginAttemptLimitedException}(429)을 던진다.
 *       먼저 세야 동시 요청이 몰려도 창당 BCrypt 가 허용 횟수로 묶인다. 판정은 계정 존재와 무관하다 — 이메일 해시로만 센다.</li>
 *   <li>{@link #clear} — 인증에 성공한 뒤 그 이메일의 창을 지운다. best-effort 다. 지금은 고객 로그인만 부른다 — 관리자 로그인은 성공해도 지우지 않는다
 *       ({@code AdminAuthWebFacade}).</li>
 *   <li>받은 이메일에 {@link Member#canonicalEmail} 을 적용한 값으로 센다 — 저장 · 조회 키와 같은 형태다. 멱등이라 이미 정규화한 값은 그대로다.</li>
 *   <li>꺼져 있으면({@code auth.login-guard.enabled=false}) 두 메서드 모두 포트를 부르지 않는다.</li>
 *   <li>관리자 OTP 코드 시도(#238)는 {@link #admitAdminOtp} · {@link #clearAdminOtp}(재인증) · {@link #admitAdminOtpLogin}(로그인 2단계, 지우지 않는다)로 따로 받는다 —
 *       주체는 memberId 이고, 꺼져 있어도 센다(fail-closed, 429 {@code AUTH_043}).</li>
 * </ul>
 *
 * <p>저장소(Redis) 장애 — 종류별 정책은 {@link #failOpen} 하나가 정한다(종류가 늘면 switch 가 컴파일에서 결정을 요구한다).
 * 로그인 · 관리자 로그인은 <b>fail-closed</b>(503 {@code AUTH_013} 을 그대로 올린다). 고객 로그인은 fail-open 이면 "맞는 비밀번호 → 토큰 저장 503,
 * 틀린 비밀번호 → 400" 으로 비밀번호 오라클이 열리고, refresh 저장 때문에 어차피 Redis 없이 성공하지 못한다. 관리자 로그인은 어드민 경로를
 * fail-closed 로 통일한다(게이트웨이 차단 목록 {@code security-guide.md} §7-1 과 같다). 회원가입은 <b>fail-open</b> 이다 — 가입은 Redis 와 무관한 API 라
 * 시도 제한 장애가 가입 장애가 되지 않게 한다 (D5).
 *
 * <p>로그 — 잠금으로 바뀌는 순간(창 안의 허용 횟수 + 1 번째)에만 info 로 남기고 이후 거절은 debug 다. 이메일 대신 짧은 꼬리표({@link LoginAttemptCounterPort#subjectTag})만 남긴다.
 * 이 Processor 의 장애 warn(fail-open · clear 실패)은 warn 마다 1분에 한 번만 남긴다 — 공격 중 로그가 요청 수만큼 쌓이지 않게.
 * 저장소 어댑터의 공통 warn({@code token store unavailable})은 따로이고 실패마다 남는다.
 */
@Slf4j
@Component
public class LoginGuardProcessor {

    /** 같은 장애 warn 사이의 최소 간격. */
    static final Duration WARN_INTERVAL = Duration.ofMinutes(1);

    private static final long NEVER = Long.MIN_VALUE;

    private final LoginAttemptCounterPort loginAttemptCounterPort;
    private final LoginGuardPolicy loginGuardPolicy;
    private final Clock clock;

    private final AtomicLong lastClearWarnAt = new AtomicLong(NEVER);
    private final AtomicLong lastFailOpenWarnAt = new AtomicLong(NEVER);

    public LoginGuardProcessor(LoginAttemptCounterPort loginAttemptCounterPort, LoginGuardPolicy loginGuardPolicy, Clock clock) {
        this.loginAttemptCounterPort = loginAttemptCounterPort;
        this.loginGuardPolicy = loginGuardPolicy;
        this.clock = clock;
    }

    /** X-Client-Subject 계약: 없거나 형식이 다르면 IP 차원은 건너뛴다. 이메일 카운터는 별도로 유지한다. */
    public void admitSignUpClient(String subject) {
        if (subject != null && subject.matches("[A-Za-z0-9_-]{22}")) {
            admit(AttemptKind.SIGN_UP_IP, subject);
        }
    }

    /**
     * 시도 1회를 세고 허용 횟수를 넘겼으면 거절한다.
     *
     * @param normalizedEmail 회원 조회 · {@link #clear} 와 같은 이메일. 여기서 {@link Member#canonicalEmail} 을 다시 적용한다(멱등)
     * @throws LoginAttemptLimitedException 허용 횟수를 넘겼다 — 로그인 · 관리자 로그인 {@code AUTH_040}, 회원가입 {@code AUTH_041}
     * @throws MemberException              {@code AUTH_013} — 로그인 · 관리자 로그인에서 저장소에 닿지 못했다(fail-closed). 회원가입은 던지지 않고 통과시킨다
     */
    public void admit(AttemptKind kind, String normalizedEmail) {
        rejectAdminOtp(kind);
        if (!loginGuardPolicy.enabled()) {
            return;
        }
        String email = kind == AttemptKind.SIGN_UP_IP ? normalizedEmail : Member.canonicalEmail(normalizedEmail);
        count(kind, email);
    }

    /**
     * 관리자 OTP 코드 시도 1회를 세고 허용 횟수를 넘겼으면 거절한다 (#238) — 등록 확인 · 해제 · 복구 코드 재발급이 비밀번호 · 코드를
     * 보기 전에 부른다. 로그인 2단계는 이 창이 아니라 {@link #admitAdminOtpLogin} 이다({@link AttemptKind#ADMIN_OTP_LOGIN}). 주체는 관리자 memberId 다.
     * {@code auth.login-guard.enabled=false} 여도 센다 — 비상 스위치가 6자리 코드 대입까지 열면 안 된다.
     * 저장소 장애는 fail-closed(503 {@code AUTH_013})다.
     *
     * @throws LoginAttemptLimitedException 429 {@code AUTH_043}
     */
    public void admitAdminOtp(long adminMemberId) {
        count(AttemptKind.ADMIN_OTP, String.valueOf(adminMemberId));
    }

    /** OTP 코드로 성공한 관리자의 창을 지운다 (#238). best-effort 규칙은 {@link #clear} 와 같고, 켜짐 여부와 무관하다. */
    public void clearAdminOtp(long adminMemberId) {
        reset(AttemptKind.ADMIN_OTP, String.valueOf(adminMemberId));
    }

    /**
     * 관리자 로그인 2단계 코드 시도 1회를 세고 허용 횟수를 넘겼으면 거절한다 (#238 PR-B) — 챌린지로 관리자를 안 뒤, 코드를 보기 전에 부른다.
     * 재인증 창({@link #admitAdminOtp})과 따로 센다 — 같은 창이면 access 토큰만 가진 사람이 틀린 재인증으로 진짜 관리자의 로그인을 잠근다.
     * 짝이 되는 clear 는 없다 — 성공해도 지우지 않는다({@link AttemptKind#ADMIN_OTP_LOGIN}). 켜짐 · 장애 규칙은 {@link #admitAdminOtp} 와 같다.
     *
     * @throws LoginAttemptLimitedException 429 {@code AUTH_043}
     */
    public void admitAdminOtpLogin(long adminMemberId) {
        count(AttemptKind.ADMIN_OTP_LOGIN, String.valueOf(adminMemberId));
    }

    private void count(AttemptKind kind, String subject) {
        LoginAttemptPolicy policy = loginGuardPolicy.of(kind);
        LoginAttemptOutcome outcome;
        try {
            outcome = loginAttemptCounterPort.record(kind, subject, policy.window());
        } catch (MemberException failure) {
            if (isStoreUnavailable(failure) && failOpen(kind)) {
                if (shouldWarn(lastFailOpenWarnAt)) {
                    log.warn("attempt counter unavailable kind={} — admitting without limit (fail-open, logged at most once per minute)", kind);
                }
                return;
            }
            throw failure;
        }
        if (!outcome.exceeds(policy.maxAttempts())) {
            return;
        }
        if (outcome.count() == policy.maxAttempts() + 1L) {
            String tag = switch (kind) {
                case SIGN_UP_IP -> subject.substring(0, 8);
                // memberId 는 개인정보가 아니고, 감사 로그(target=member:{id})와 맞춰 볼 수 있게 그대로 남긴다
                case ADMIN_OTP, ADMIN_OTP_LOGIN -> subject;
                case LOGIN, ADMIN_LOGIN, SIGN_UP -> loginAttemptCounterPort.subjectTag(subject);
            };
            log.info("attempt limit reached kind={} subject={} retryAfterMs={}", kind, tag, outcome.retryAfter().toMillis());
        } else {
            log.debug("attempt limited kind={}", kind);
        }
        throw new LoginAttemptLimitedException(limitedCode(kind), outcome.retryAfter());
    }

    /**
     * 인증에 성공한 이메일의 창을 지운다 — 인증에 성공했을 때만 부른다. 저장소 장애({@code AUTH_013})는 응답을 바꾸지 않는다(best-effort): 맞는 비밀번호에서만
     * 나오는 새 응답을 만들면 그 자체가 오라클이 된다. 남은 창은 TTL 로 끝난다. warn 은 1분에 한 번이다. 저장소 장애가 아닌 오류(버그)는 삼키지 않고 그대로 던진다.
     */
    public void clear(AttemptKind kind, String normalizedEmail) {
        rejectAdminOtp(kind);
        if (!loginGuardPolicy.enabled()) {
            return;
        }
        reset(kind, Member.canonicalEmail(normalizedEmail));
    }

    private void reset(AttemptKind kind, String subject) {
        try {
            loginAttemptCounterPort.reset(kind, subject);
        } catch (MemberException failure) {
            if (!isStoreUnavailable(failure)) {
                throw failure;
            }
            if (shouldWarn(lastClearWarnAt)) {
                log.warn("attempt counter reset failed kind={} code={} (best-effort, logged at most once per minute)", kind, failure.getErrorCode().getCode());
            }
        }
    }

    /**
     * 저장소 장애 때 세지 않고 통과시킬지(fail-open). 종류가 늘면 이 switch 가 컴파일에서 막는다 — 장애 정책을 정하지 않은 종류가 생기지 않게.
     * 로그인 · 관리자 로그인은 막는다(비밀번호 오라클 · 어드민 fail-closed), 회원가입은 통과시킨다(가입은 Redis 와 무관한 API).
     */
    private static boolean failOpen(AttemptKind kind) {
        return switch (kind) {
            case LOGIN, ADMIN_LOGIN, ADMIN_OTP, ADMIN_OTP_LOGIN -> false;
            case SIGN_UP, SIGN_UP_IP -> true;
        };
    }

    /**
     * OTP 시도는 이메일 주체 · 켜짐 스위치를 따르지 않아 {@link #admitAdminOtp} · {@link #clearAdminOtp} · {@link #admitAdminOtpLogin} 로만 받는다 —
     * 잘못 부르면 조용히 꺼지지 않게 바로 실패한다.
     */
    private static void rejectAdminOtp(AttemptKind kind) {
        if (kind == AttemptKind.ADMIN_OTP || kind == AttemptKind.ADMIN_OTP_LOGIN) {
            throw new IllegalArgumentException("admin otp attempts go through admitAdminOtp/clearAdminOtp/admitAdminOtpLogin");
        }
    }

    private static boolean isStoreUnavailable(MemberException failure) {
        return failure.getErrorCode() == MemberErrorCode.TOKEN_STORE_UNAVAILABLE;
    }

    private static MemberErrorCode limitedCode(AttemptKind kind) {
        return switch (kind) {
            case LOGIN, ADMIN_LOGIN -> MemberErrorCode.LOGIN_ATTEMPT_LIMITED;
            case SIGN_UP, SIGN_UP_IP -> MemberErrorCode.SIGN_UP_ATTEMPT_LIMITED;
            case ADMIN_OTP, ADMIN_OTP_LOGIN -> MemberErrorCode.OTP_ATTEMPT_LIMITED;
        };
    }

    /** 마지막 warn 에서 {@link #WARN_INTERVAL} 이 지났으면 이번 호출이 남긴다 — 동시에 와도 한 스레드만 이긴다. */
    private boolean shouldWarn(AtomicLong lastWarnAt) {
        long now = clock.millis();
        long last = lastWarnAt.get();
        boolean due = last == NEVER || now - last >= WARN_INTERVAL.toMillis();
        return due && lastWarnAt.compareAndSet(last, now);
    }
}
