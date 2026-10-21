package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

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
            disabled.admit(kind, EMAIL);
            disabled.clear(kind, EMAIL);
        }

        verifyNoInteractions(port);
    }

    @ParameterizedTest
    @EnumSource(AttemptKind.class)
    @DisplayName("종류에 맞는 창 길이로 센다 — 로그인 · 관리자 로그인 15분, 가입 10분")
    void recordsWithKindWindow(AttemptKind kind) {
        Duration window = kind == AttemptKind.SIGN_UP ? SIGN_UP_WINDOW : LOGIN_WINDOW;
        given(port.record(kind, EMAIL, window)).willReturn(new LoginAttemptOutcome(1, window));

        processor.admit(kind, EMAIL);

        verify(port).record(kind, EMAIL, window);
    }

    @Test
    @DisplayName("허용 횟수까지는 통과하고 그다음부터 429 AUTH_040 — 남은 창 시간을 싣는다")
    void loginLimitedAfterMaxAttempts() {
        given(port.record(AttemptKind.LOGIN, EMAIL, LOGIN_WINDOW)).willReturn(new LoginAttemptOutcome(5, Duration.ofMinutes(10)));
        assertThatCode(() -> processor.admit(AttemptKind.LOGIN, EMAIL)).doesNotThrowAnyException();

        given(port.record(AttemptKind.LOGIN, EMAIL, LOGIN_WINDOW)).willReturn(new LoginAttemptOutcome(6, Duration.ofMillis(90_001)));
        assertThatThrownBy(() -> processor.admit(AttemptKind.LOGIN, EMAIL))
            .isInstanceOfSatisfying(LoginAttemptLimitedException.class, limited -> {
                assertThat(limited.getErrorCode()).isEqualTo(MemberErrorCode.LOGIN_ATTEMPT_LIMITED);
                assertThat(limited.getRetryAfter()).isEqualTo(Duration.ofMillis(90_001));
                assertThat(limited.retryAfterSeconds()).isEqualTo(91);
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

    @ParameterizedTest(name = "{0}ms → {1}초")
    @CsvSource({"0, 1", "1, 1", "999, 1", "1000, 1", "1001, 2", "900000, 900"})
    @DisplayName("Retry-After 는 max(1, ceil(ms/1000)) 이다")
    void retryAfterSecondsRoundsUp(long millis, long seconds) {
        assertThat(new LoginAttemptLimitedException(MemberErrorCode.LOGIN_ATTEMPT_LIMITED, Duration.ofMillis(millis)).retryAfterSeconds()).isEqualTo(seconds);
    }

    @Test
    @DisplayName("잠금으로 바뀌는 순간(허용 + 1 번째)에만 info 를 남기고 해시 앞 8자만 싣는다 — 이후 거절은 info 를 남기지 않는다")
    void logsOnlyAtTransition(CapturedOutput output) {
        given(port.subjectTag(EMAIL)).willReturn("AbCdEfGh");
        given(port.record(AttemptKind.LOGIN, EMAIL, LOGIN_WINDOW))
            .willReturn(new LoginAttemptOutcome(6, Duration.ofMinutes(5)), new LoginAttemptOutcome(7, Duration.ofMinutes(5)));

        assertThatThrownBy(() -> processor.admit(AttemptKind.LOGIN, EMAIL)).isInstanceOf(LoginAttemptLimitedException.class);
        assertThatThrownBy(() -> processor.admit(AttemptKind.LOGIN, EMAIL)).isInstanceOf(LoginAttemptLimitedException.class);

        assertThat(output.getOut().split("attempt limit reached", -1)).hasSize(2);
        assertThat(output.getOut()).contains("kind=LOGIN subject=AbCdEfGh retryAfterSeconds=300").doesNotContain(EMAIL);
        verify(port, org.mockito.Mockito.times(1)).subjectTag(EMAIL);
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

        assertThatCode(() -> processor.admit(AttemptKind.SIGN_UP, EMAIL)).doesNotThrowAnyException();
        assertThatCode(() -> processor.admit(AttemptKind.SIGN_UP, EMAIL)).doesNotThrowAnyException();
        clock.advance(Duration.ofSeconds(59));
        processor.admit(AttemptKind.SIGN_UP, EMAIL);
        assertThat(output.getOut().split("sign-up attempt counter unavailable", -1)).hasSize(2);

        clock.advance(Duration.ofSeconds(1));
        processor.admit(AttemptKind.SIGN_UP, EMAIL);
        assertThat(output.getOut().split("sign-up attempt counter unavailable", -1)).hasSize(3);
    }

    @Test
    @DisplayName("가입도 장애가 아닌 오류는 삼키지 않는다 — fail-open 은 저장소 장애(AUTH_013)만이다")
    void signUpDoesNotSwallowOtherErrors() {
        willThrow(new IllegalStateException("bug")).given(port).record(any(), anyString(), any());

        assertThatThrownBy(() -> processor.admit(AttemptKind.SIGN_UP, EMAIL)).isInstanceOf(IllegalStateException.class);
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
    @DisplayName("clear 실패는 best-effort — 예외를 올리지 않고 warn 은 1분에 한 번, 이메일 없이 예외 타입만 남긴다")
    void clearFailureIsBestEffort(CapturedOutput output) {
        willThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE)).given(port).reset(any(), anyString());

        assertThatCode(() -> processor.clear(AttemptKind.LOGIN, EMAIL)).doesNotThrowAnyException();
        assertThatCode(() -> processor.clear(AttemptKind.LOGIN, EMAIL)).doesNotThrowAnyException();
        assertThat(output.getOut().split("attempt counter reset failed", -1)).hasSize(2);
        assertThat(output.getOut()).contains("type=" + MemberException.class.getName()).doesNotContain(EMAIL);

        clock.advance(Duration.ofMinutes(1));
        willThrow(new IllegalStateException("unexpected")).given(port).reset(any(), anyString());
        assertThatCode(() -> processor.clear(AttemptKind.ADMIN_LOGIN, EMAIL)).doesNotThrowAnyException();
        assertThat(output.getOut().split("attempt counter reset failed", -1)).hasSize(3);
    }
}
