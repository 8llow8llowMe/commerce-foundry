package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.application.exception.LoginAttemptLimitedException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.LoginAttemptCounterPort;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptOutcome;
import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptPolicy;
import com.commercefoundry.domainlayer.member.domain.model.LoginGuardPolicy;
import com.commercefoundry.support.AdjustableClock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 시도 제한 판정 — 허용/초과 경계 · 종류별 코드 · 정규화 · 장애 정책(fail-closed / fail-open) · clear best-effort · 로그 빈도.
 * {@code Retry-After} 초 변환은 헤더 형식이라 핸들러가 하고 컨트롤러 테스트가 본다 — 여기서는 남은 시간 원값만 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class LoginGuardProcessorTest {

    private static final String EMAIL = "member@example.com";
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final Duration SIGN_UP_WINDOW = Duration.ofMinutes(10);
    private static final LoginGuardPolicy ENABLED = new LoginGuardPolicy(true, new LoginAttemptPolicy(5, LOGIN_WINDOW), new LoginAttemptPolicy(3, SIGN_UP_WINDOW));

    private final LoginAttemptCounterPort port = mock(LoginAttemptCounterPort.class);
    private final AdjustableClock clock = new AdjustableClock();
    private final LoginGuardProcessor processor = new LoginGuardProcessor(port, ENABLED, clock);

    @Test
    @DisplayName("꺼져 있으면 admit · clear 모두 포트를 부르지 않는다")
    void disabledNeverCallsPort() {
        LoginGuardProcessor disabled = new LoginGuardProcessor(port, new LoginGuardPolicy(false, ENABLED.login(), ENABLED.signUp()), clock);

        for (AttemptKind kind : AttemptKind.values()) {
            if (kind == AttemptKind.ADMIN_OTP || kind == AttemptKind.ADMIN_OTP_LOGIN) {
                continue;
            }
            disabled.admit(kind, EMAIL);
            disabled.clear(kind, EMAIL);
        }

        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("관리자 OTP 는 꺼져 있어도 memberId 로 센다 — 비상 스위치가 6자리 코드 대입을 열지 않는다. 로그인과 같은 5회 · 15분이다")
    void adminOtpCountsEvenWhenDisabled() {
        LoginGuardProcessor disabled = new LoginGuardProcessor(port, new LoginGuardPolicy(false, ENABLED.login(), ENABLED.signUp()), clock);
        given(port.record(AttemptKind.ADMIN_OTP, "4242", LOGIN_WINDOW)).willReturn(new LoginAttemptOutcome(5, LOGIN_WINDOW));

        disabled.admitAdminOtp(4242L);
        disabled.clearAdminOtp(4242L);

        verify(port).record(AttemptKind.ADMIN_OTP, "4242", LOGIN_WINDOW);
        verify(port).reset(AttemptKind.ADMIN_OTP, "4242");
    }

    @Test
    @DisplayName("관리자 OTP 초과는 429 AUTH_043 이고, 잠금 전환 로그에는 memberId 를 그대로 남긴다(이메일 꼬리표를 만들지 않는다)")
    void adminOtpLimited(CapturedOutput output) {
        given(port.record(AttemptKind.ADMIN_OTP, "4242", LOGIN_WINDOW)).willReturn(new LoginAttemptOutcome(6, Duration.ofMinutes(3)));

        assertThatThrownBy(() -> processor.admitAdminOtp(4242L))
            .isInstanceOfSatisfying(LoginAttemptLimitedException.class, limited -> {
                assertThat(limited.getErrorCode()).isEqualTo(MemberErrorCode.OTP_ATTEMPT_LIMITED);
                assertThat(limited.getRetryAfter()).isEqualTo(Duration.ofMinutes(3));
            });
        assertThat(output.getOut()).contains("kind=ADMIN_OTP subject=4242 retryAfterMs=180000");
        verify(port, never()).subjectTag(anyString());
    }

    @Test
    @DisplayName("관리자 OTP 계수기 장애는 fail-closed(AUTH_013), 창 비우기 장애는 삼킨다(best-effort)")
    void adminOtpFailsClosed() {
        MemberException unavailable = new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        willThrow(unavailable).given(port).record(any(), anyString(), any());
        willThrow(unavailable).given(port).reset(any(), anyString());

        assertThatThrownBy(() -> processor.admitAdminOtp(4242L)).isSameAs(unavailable);
        assertThatCode(() -> processor.clearAdminOtp(4242L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ADMIN_OTP · ADMIN_OTP_LOGIN 을 이메일 경로(admit · clear)로 부르면 바로 실패한다 — 켜짐 스위치 · 이메일 정규화를 타지 않게")
    void adminOtpRejectsEmailPath() {
        assertThatThrownBy(() -> processor.admit(AttemptKind.ADMIN_OTP, "4242")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.clear(AttemptKind.ADMIN_OTP, "4242")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.admit(AttemptKind.ADMIN_OTP_LOGIN, "4242")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.clear(AttemptKind.ADMIN_OTP_LOGIN, "4242")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("로그인 2단계는 재인증과 다른 창(ADMIN_OTP_LOGIN)으로 꺼져 있어도 센다 — 초과는 429 AUTH_043, 계수기 장애는 fail-closed 다")
    void adminOtpLoginCountsInOwnWindow(CapturedOutput output) {
        LoginGuardProcessor disabled = new LoginGuardProcessor(port, new LoginGuardPolicy(false, ENABLED.login(), ENABLED.signUp()), clock);
        given(port.record(AttemptKind.ADMIN_OTP_LOGIN, "4242", LOGIN_WINDOW))
            .willReturn(new LoginAttemptOutcome(5, LOGIN_WINDOW), new LoginAttemptOutcome(6, Duration.ofMinutes(2)));

        disabled.admitAdminOtpLogin(4242L);
        assertThatThrownBy(() -> disabled.admitAdminOtpLogin(4242L))
            .isInstanceOfSatisfying(LoginAttemptLimitedException.class, limited -> {
                assertThat(limited.getErrorCode()).isEqualTo(MemberErrorCode.OTP_ATTEMPT_LIMITED);
                assertThat(limited.getRetryAfter()).isEqualTo(Duration.ofMinutes(2));
            });

        verify(port, never()).record(eq(AttemptKind.ADMIN_OTP), anyString(), any());
        verify(port, never()).reset(any(), anyString());
        assertThat(output.getOut()).contains("kind=ADMIN_OTP_LOGIN subject=4242 retryAfterMs=120000");

        MemberException unavailable = new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        willThrow(unavailable).given(port).record(eq(AttemptKind.ADMIN_OTP_LOGIN), eq("77"), any());
        assertThatThrownBy(() -> processor.admitAdminOtpLogin(77L)).isSameAs(unavailable);
    }

    @ParameterizedTest
    @EnumSource(value = AttemptKind.class, names = {"LOGIN", "ADMIN_LOGIN", "SIGN_UP"})
    @DisplayName("종류에 맞는 창 길이로 센다 — 로그인 · 관리자 로그인 15분, 가입 10분")
    void recordsWithKindWindow(AttemptKind kind) {
        Duration window = kind == AttemptKind.SIGN_UP ? SIGN_UP_WINDOW : LOGIN_WINDOW;
        given(port.record(kind, EMAIL, window)).willReturn(new LoginAttemptOutcome(1, window));

        processor.admit(kind, EMAIL);

        verify(port).record(kind, EMAIL, window);
    }

    @ParameterizedTest
    @EnumSource(value = AttemptKind.class, names = {"LOGIN", "ADMIN_LOGIN", "SIGN_UP"})
    @DisplayName("받은 이메일에 Member.canonicalEmail 을 적용해 센다 · 지운다 · 꼬리표를 만든다 — 대소문자 · 앞뒤 공백 변형도 한 창이다(멱등)")
    void canonicalizesEmail(AttemptKind kind) {
        Duration window = kind == AttemptKind.SIGN_UP ? SIGN_UP_WINDOW : LOGIN_WINDOW;
        int maxAttempts = kind == AttemptKind.SIGN_UP ? 3 : 5;
        given(port.record(kind, EMAIL, window)).willReturn(new LoginAttemptOutcome(1, window), new LoginAttemptOutcome(maxAttempts + 1L, window));

        processor.admit(kind, " Member@Example.COM ");
        assertThatThrownBy(() -> processor.admit(kind, EMAIL)).isInstanceOf(LoginAttemptLimitedException.class);
        processor.clear(kind, "\tMEMBER@example.com ");

        verify(port, times(2)).record(kind, EMAIL, window);
        verify(port).subjectTag(EMAIL);
        verify(port).reset(kind, EMAIL);
    }

    @Test
    @DisplayName("허용 횟수까지는 통과하고 그다음부터 429 AUTH_040 — 남은 창 시간을 원값 그대로 싣는다")
    void loginLimitedAfterMaxAttempts() {
        given(port.record(AttemptKind.LOGIN, EMAIL, LOGIN_WINDOW)).willReturn(new LoginAttemptOutcome(5, Duration.ofMinutes(10)));
        assertThatCode(() -> processor.admit(AttemptKind.LOGIN, EMAIL)).doesNotThrowAnyException();

        given(port.record(AttemptKind.LOGIN, EMAIL, LOGIN_WINDOW)).willReturn(new LoginAttemptOutcome(6, Duration.ofMillis(90_001)));
        assertThatThrownBy(() -> processor.admit(AttemptKind.LOGIN, EMAIL))
            .isInstanceOfSatisfying(LoginAttemptLimitedException.class, limited -> {
                assertThat(limited.getErrorCode()).isEqualTo(MemberErrorCode.LOGIN_ATTEMPT_LIMITED);
                assertThat(limited.getRetryAfter()).isEqualTo(Duration.ofMillis(90_001));
                assertThat(limited.getMessage()).isEqualTo("로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.").doesNotContain(EMAIL);
            });
    }

    @Test
    @DisplayName("관리자 로그인 초과도 같은 AUTH_040, 가입 초과는 AUTH_041 이다")
    void limitedCodePerKind() {
        given(port.record(AttemptKind.ADMIN_LOGIN, EMAIL, LOGIN_WINDOW)).willReturn(new LoginAttemptOutcome(6, Duration.ofMinutes(1)));
        given(port.record(AttemptKind.SIGN_UP, EMAIL, SIGN_UP_WINDOW)).willReturn(new LoginAttemptOutcome(4, Duration.ofMinutes(1)));

        assertThatThrownBy(() -> processor.admit(AttemptKind.ADMIN_LOGIN, EMAIL))
            .isInstanceOfSatisfying(LoginAttemptLimitedException.class, limited -> assertThat(limited.getErrorCode()).isEqualTo(MemberErrorCode.LOGIN_ATTEMPT_LIMITED));
        assertThatThrownBy(() -> processor.admit(AttemptKind.SIGN_UP, EMAIL))
            .isInstanceOfSatisfying(LoginAttemptLimitedException.class, limited -> assertThat(limited.getErrorCode()).isEqualTo(MemberErrorCode.SIGN_UP_ATTEMPT_LIMITED));
    }

    @Test
    @DisplayName("잠금으로 바뀌는 순간(허용 + 1 번째)에만 info 를 남기고 꼬리표 · 남은 ms 원값만 싣는다 — 이후 거절은 info 를 남기지 않는다")
    void logsOnlyAtTransition(CapturedOutput output) {
        given(port.subjectTag(EMAIL)).willReturn("AbCdEfGh");
        given(port.record(AttemptKind.LOGIN, EMAIL, LOGIN_WINDOW))
            .willReturn(new LoginAttemptOutcome(6, Duration.ofMinutes(5)), new LoginAttemptOutcome(7, Duration.ofMinutes(5)));

        assertThatThrownBy(() -> processor.admit(AttemptKind.LOGIN, EMAIL)).isInstanceOf(LoginAttemptLimitedException.class);
        assertThatThrownBy(() -> processor.admit(AttemptKind.LOGIN, EMAIL)).isInstanceOf(LoginAttemptLimitedException.class);

        assertThat(output.getOut().split("attempt limit reached", -1)).hasSize(2);
        assertThat(output.getOut()).contains("kind=LOGIN subject=AbCdEfGh retryAfterMs=300000").doesNotContain(EMAIL);
        verify(port, times(1)).subjectTag(EMAIL);
    }

    @ParameterizedTest
    @EnumSource(value = AttemptKind.class, names = {"LOGIN", "ADMIN_LOGIN"})
    @DisplayName("로그인 · 관리자 로그인은 계수기 장애(AUTH_013)를 그대로 올린다 — fail-closed")
    void loginFailsClosed(AttemptKind kind) {
        MemberException unavailable = new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        willThrow(unavailable).given(port).record(any(), anyString(), any());

        assertThatThrownBy(() -> processor.admit(kind, EMAIL)).isSameAs(unavailable);
    }

    @Test
    @DisplayName("가입은 계수기 장애(AUTH_013)면 통과시킨다(fail-open) — warn 은 1분에 한 번이다")
    void signUpFailsOpenWithThrottledWarn(CapturedOutput output) {
        willThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE)).given(port).record(any(), anyString(), any());
        String warning = "attempt counter unavailable kind=SIGN_UP";

        assertThatCode(() -> processor.admit(AttemptKind.SIGN_UP, EMAIL)).doesNotThrowAnyException();
        assertThatCode(() -> processor.admit(AttemptKind.SIGN_UP, EMAIL)).doesNotThrowAnyException();
        clock.advance(Duration.ofSeconds(59));
        processor.admit(AttemptKind.SIGN_UP, EMAIL);
        assertThat(output.getOut().split(warning, -1)).hasSize(2);

        clock.advance(Duration.ofSeconds(1));
        processor.admit(AttemptKind.SIGN_UP, EMAIL);
        assertThat(output.getOut().split(warning, -1)).hasSize(3);
        assertThat(output.getOut()).doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("가입도 저장소 장애가 아닌 오류는 삼키지 않는다 — fail-open 은 저장소 장애(AUTH_013)만이다")
    void signUpDoesNotSwallowOtherErrors() {
        willThrow(new IllegalStateException("bug")).given(port).record(any(), anyString(), any());
        assertThatThrownBy(() -> processor.admit(AttemptKind.SIGN_UP, EMAIL)).isInstanceOf(IllegalStateException.class);

        MemberException other = new MemberException(MemberErrorCode.LOGIN_FAILED);
        willThrow(other).given(port).record(any(), anyString(), any());
        assertThatThrownBy(() -> processor.admit(AttemptKind.SIGN_UP, EMAIL)).isSameAs(other);
    }

    @ParameterizedTest
    @EnumSource(value = AttemptKind.class, names = {"LOGIN", "ADMIN_LOGIN"})
    @DisplayName("clear 는 그 종류의 창을 지운다")
    void clearResets(AttemptKind kind) {
        processor.clear(kind, EMAIL);

        verify(port).reset(kind, EMAIL);
        verify(port, never()).record(any(), anyString(), any());
    }

    @Test
    @DisplayName("clear 의 저장소 장애(AUTH_013)는 best-effort — 예외를 올리지 않고 warn 은 1분에 한 번, 이메일 없이 코드만 남긴다")
    void clearStoreFailureIsBestEffort(CapturedOutput output) {
        willThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE)).given(port).reset(any(), anyString());

        assertThatCode(() -> processor.clear(AttemptKind.LOGIN, EMAIL)).doesNotThrowAnyException();
        assertThatCode(() -> processor.clear(AttemptKind.LOGIN, EMAIL)).doesNotThrowAnyException();
        assertThat(output.getOut().split("attempt counter reset failed", -1)).hasSize(2);
        assertThat(output.getOut()).contains("kind=LOGIN code=AUTH_013").doesNotContain(EMAIL);

        clock.advance(Duration.ofMinutes(1));
        assertThatCode(() -> processor.clear(AttemptKind.LOGIN, EMAIL)).doesNotThrowAnyException();
        assertThat(output.getOut().split("attempt counter reset failed", -1)).hasSize(3);
    }

    @Test
    @DisplayName("clear 는 저장소 장애가 아닌 오류(다른 코드의 MemberException · 런타임 오류)를 삼키지 않고 그대로 던진다")
    void clearDoesNotSwallowOtherErrors(CapturedOutput output) {
        MemberException other = new MemberException(MemberErrorCode.LOGIN_FAILED);
        willThrow(other).given(port).reset(any(), anyString());
        assertThatThrownBy(() -> processor.clear(AttemptKind.LOGIN, EMAIL)).isSameAs(other);

        IllegalStateException bug = new IllegalStateException("unexpected");
        willThrow(bug).given(port).reset(any(), anyString());
        assertThatThrownBy(() -> processor.clear(AttemptKind.LOGIN, EMAIL)).isSameAs(bug);

        assertThat(output.getOut()).doesNotContain("attempt counter reset failed");
    }

    @Test
    @DisplayName("가입 IP 식별자는 대소문자를 보존하고 별도 30회 창에서 제한한다")
    void signUpIpUsesOpaqueSubject() {
        String subject = "AbCdEfGhIjKlMnOpQrStUw";
        given(port.record(AttemptKind.SIGN_UP_IP, subject, SIGN_UP_WINDOW))
            .willReturn(new LoginAttemptOutcome(30, SIGN_UP_WINDOW), new LoginAttemptOutcome(31, Duration.ofSeconds(20)));

        processor.admitSignUpClient(subject);
        assertThatThrownBy(() -> processor.admitSignUpClient(subject))
            .isInstanceOfSatisfying(LoginAttemptLimitedException.class,
                limited -> assertThat(limited.getErrorCode()).isEqualTo(MemberErrorCode.SIGN_UP_ATTEMPT_LIMITED));
        verify(port, times(2)).record(AttemptKind.SIGN_UP_IP, subject, SIGN_UP_WINDOW);
        verify(port, never()).subjectTag(anyString());
    }

    @Test
    @DisplayName("없거나 잘못된 IP 헤더는 IP 차원만 건너뛴다")
    void missingOrMalformedClientSubject() {
        processor.admitSignUpClient(null);
        processor.admitSignUpClient("");
        processor.admitSignUpClient("1.2.3.4");
        processor.admitSignUpClient("A".repeat(23));
        processor.admitSignUpClient("한".repeat(22));
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("가입 IP 계수기 장애도 가입 이메일과 같이 fail-open 이다")
    void ipStoreUnavailableIsFailOpen() {
        String subject = "AbCdEfGhIjKlMnOpQrStUw";
        given(port.record(AttemptKind.SIGN_UP_IP, subject, SIGN_UP_WINDOW))
            .willThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE));
        assertThatCode(() -> processor.admitSignUpClient(subject)).doesNotThrowAnyException();
    }

}
