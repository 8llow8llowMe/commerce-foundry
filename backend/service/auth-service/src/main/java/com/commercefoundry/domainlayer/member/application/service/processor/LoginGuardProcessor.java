package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.LoginAttemptLimitedException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.LoginAttemptCounterPort;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptOutcome;
import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptPolicy;
import com.commercefoundry.domainlayer.member.domain.model.LoginGuardPolicy;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 로그인 · 관리자 로그인 · 회원가입 시도 제한 (#66, {@code security-guide.md} §12). Facade 는 계수기 포트를 직접 부르지 않고 이 Processor 를 거친다 (ArchUnit H11).
 *
 * <ul>
 *   <li>{@link #admit} — 시도 1회를 먼저 센 뒤(인증 전) 허용 횟수를 넘겼으면 {@link LoginAttemptLimitedException}(429)을 던진다.
 *       먼저 세야 동시 요청이 몰려도 창당 BCrypt 가 허용 횟수로 묶인다. 판정은 계정 존재와 무관하다 — 이메일 해시로만 센다.</li>
 *   <li>{@link #clear} — 인증에 성공한 뒤 그 이메일의 창을 지운다. best-effort 다.</li>
 *   <li>꺼져 있으면({@code auth.login-guard.enabled=false}) 두 메서드 모두 포트를 부르지 않는다.</li>
 * </ul>
 *
 * <p>저장소(Redis) 장애 — 로그인 · 관리자 로그인은 <b>fail-closed</b>(503 {@code AUTH_013} 을 그대로 올린다). fail-open 이면 "맞는 비밀번호 → 토큰 저장 503,
 * 틀린 비밀번호 → 400" 으로 비밀번호 오라클이 열리고, 로그인은 refresh 저장 때문에 어차피 Redis 없이 성공하지 못한다.
 * 회원가입은 <b>fail-open</b> 이다 — 가입은 Redis 와 무관한 API 라 시도 제한 장애가 가입 장애가 되지 않게 한다 (D5).
 *
 * <p>로그 — 잠금으로 바뀌는 순간(창 안의 허용 횟수 + 1 번째)에만 info 로 남기고 이후 거절은 debug 다. 이메일 대신 해시 앞 8자({@link LoginAttemptCounterPort#subjectTag})만 남긴다.
 * 장애 warn 은 종류마다 1분에 한 번만 남긴다 — 공격 중 로그가 요청 수만큼 쌓이지 않게.
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
    private final AtomicLong lastSignUpFailOpenWarnAt = new AtomicLong(NEVER);

    public LoginGuardProcessor(LoginAttemptCounterPort loginAttemptCounterPort, LoginGuardPolicy loginGuardPolicy, Clock clock) {
        this.loginAttemptCounterPort = loginAttemptCounterPort;
        this.loginGuardPolicy = loginGuardPolicy;
        this.clock = clock;
    }

    /**
     * 시도 1회를 세고 허용 횟수를 넘겼으면 거절한다.
     *
     * @param normalizedEmail 회원 조회 · {@link #clear} 와 같은 정규화 이메일
     * @throws LoginAttemptLimitedException 허용 횟수를 넘겼다 — 로그인 · 관리자 로그인 {@code AUTH_040}, 회원가입 {@code AUTH_041}
     * @throws MemberException              {@code AUTH_013} — 로그인 · 관리자 로그인에서 저장소에 닿지 못했다(fail-closed). 회원가입은 던지지 않고 통과시킨다
     */
    public void admit(AttemptKind kind, String normalizedEmail) {
        if (!loginGuardPolicy.enabled()) {
            return;
        }
        LoginAttemptPolicy policy = loginGuardPolicy.of(kind);
        LoginAttemptOutcome outcome;
        try {
            outcome = loginAttemptCounterPort.record(kind, normalizedEmail, policy.window());
        } catch (MemberException failure) {
            if (kind == AttemptKind.SIGN_UP && failure.getErrorCode() == MemberErrorCode.TOKEN_STORE_UNAVAILABLE) {
                if (shouldWarn(lastSignUpFailOpenWarnAt)) {
                    log.warn("sign-up attempt counter unavailable — admitting without limit (fail-open, logged at most once per minute)");
                }
                return;
            }
            throw failure;
        }
        if (!outcome.exceeds(policy.maxAttempts())) {
            return;
        }
        LoginAttemptLimitedException limited = new LoginAttemptLimitedException(limitedCode(kind), outcome.retryAfter());
        if (outcome.count() == policy.maxAttempts() + 1L) {
            log.info("attempt limit reached kind={} subject={} retryAfterSeconds={}",
                kind, loginAttemptCounterPort.subjectTag(normalizedEmail), limited.retryAfterSeconds());
        } else {
            log.debug("attempt limited kind={}", kind);
        }
        throw limited;
    }

    /**
     * 인증에 성공한 이메일의 창을 지운다 — 인증에 성공했을 때만 부른다. 실패해도 응답을 바꾸지 않는다(best-effort): 맞는 비밀번호에서만 나오는 새 응답을 만들면
     * 그 자체가 오라클이 된다. 남은 창은 TTL 로 끝난다. warn 은 1분에 한 번이다.
     */
    public void clear(AttemptKind kind, String normalizedEmail) {
        if (!loginGuardPolicy.enabled()) {
            return;
        }
        try {
            loginAttemptCounterPort.reset(kind, normalizedEmail);
        } catch (RuntimeException failure) {
            if (shouldWarn(lastClearWarnAt)) {
                log.warn("attempt counter reset failed kind={} type={} (best-effort, logged at most once per minute)", kind, failure.getClass().getName());
            }
        }
    }

    private static MemberErrorCode limitedCode(AttemptKind kind) {
        return switch (kind) {
            case LOGIN, ADMIN_LOGIN -> MemberErrorCode.LOGIN_ATTEMPT_LIMITED;
            case SIGN_UP -> MemberErrorCode.SIGN_UP_ATTEMPT_LIMITED;
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
