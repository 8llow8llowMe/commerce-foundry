package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class MemberLoginProcessorTest {

    private static final String DUMMY_HASH = "$2a$10$dummy";
    private static final Member CUSTOMER = Member.restore(1L, "a@example.com", "$2a$10$customer", "홍길동", null, MemberRole.CUSTOMER, 0L);
    private static final Member ADMIN = Member.restore(2L, "admin@example.com", "$2a$10$admin", "관리자", null, MemberRole.ADMIN, 0L);

    private final MemberRepositoryPort memberRepositoryPort = mock(MemberRepositoryPort.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private MemberLoginProcessor processor;

    @BeforeEach
    void setUp() {
        given(passwordEncoder.encode(anyString())).willReturn(DUMMY_HASH);
        processor = new MemberLoginProcessor(memberRepositoryPort, passwordEncoder);
    }

    @Test
    @DisplayName("이메일은 한 번 정규화(앞뒤 공백 제거 · 소문자)하고, 조회는 그 값을 그대로 쓴다")
    void findByNormalizedEmail() {
        given(memberRepositoryPort.findByEmail("a@example.com")).willReturn(Optional.of(CUSTOMER));

        assertThat(processor.normalizeEmail("  A@Example.COM ")).contains("a@example.com");
        assertThat(processor.findByEmail("a@example.com")).contains(CUSTOMER);
    }

    @Test
    @DisplayName("정규화할 수 없는 이메일(빈 값 · 공백만 · 전각 공백 · 254자 초과 · 소문자화로 254자 초과 · null)은 예외 없이 빈 값이다 — 조회하지 않는다")
    void outOfRuleEmailIsAbsent() {
        // U+0130 은 ROOT 소문자화로 2자가 된다 — 요청 검증(254자)을 지나도 정규화 뒤 254자를 넘는다
        String growsWhenLowercased = "İ".repeat(128) + "@example.com";

        assertThat(processor.normalizeEmail("   ")).isEmpty();
        assertThat(processor.normalizeEmail("　")).isEmpty();
        assertThat(processor.normalizeEmail("a".repeat(255))).isEmpty();
        assertThat(growsWhenLowercased.length()).isLessThanOrEqualTo(Member.EMAIL_MAX_LENGTH);
        assertThat(processor.normalizeEmail(growsWhenLowercased)).isEmpty();
        assertThat(processor.normalizeEmail(null)).isEmpty();
        verifyNoInteractions(memberRepositoryPort);
    }

    @Test
    @DisplayName("고객이고 비밀번호가 맞으면 그 회원이다")
    void authenticated() {
        given(passwordEncoder.matches("mokoyuri2026!", "$2a$10$customer")).willReturn(true);

        assertThat(processor.authenticate(Optional.of(CUSTOMER), "mokoyuri2026!")).isEqualTo(CUSTOMER);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 AUTH_001 이다")
    void wrongPassword() {
        given(passwordEncoder.matches("wrong", "$2a$10$customer")).willReturn(false);

        assertLoginFailed(() -> processor.authenticate(Optional.of(CUSTOMER), "wrong"));
    }

    @Test
    @DisplayName("회원이 없어도 더미 해시로 한 번 비교한 뒤 AUTH_001 이다 — 응답 시간으로 존재가 드러나지 않게")
    void absentMemberStillCompares() {
        assertLoginFailed(() -> processor.authenticate(Optional.empty(), "mokoyuri2026!"));

        verify(passwordEncoder).matches("mokoyuri2026!", DUMMY_HASH);
    }

    @Test
    @DisplayName("관리자 계정은 비밀번호가 맞아도 AUTH_001 이다 — 비교는 똑같이 한다")
    void adminIsRejected() {
        given(passwordEncoder.matches("mokoyuri2026!", "$2a$10$admin")).willReturn(true);

        assertLoginFailed(() -> processor.authenticate(Optional.of(ADMIN), "mokoyuri2026!"));
        verify(passwordEncoder).matches("mokoyuri2026!", "$2a$10$admin");
    }

    @Test
    @DisplayName("인코더가 받지 않는 입력(72바이트 초과 등)은 틀린 비밀번호다 — 500 이 되지 않는다")
    void encoderRejectionIsMismatch() {
        given(passwordEncoder.matches(anyString(), anyString())).willThrow(new IllegalArgumentException("password cannot be more than 72 bytes"));

        assertLoginFailed(() -> processor.authenticate(Optional.of(CUSTOMER), "x".repeat(100)));
    }

    @Test
    @DisplayName("재발급의 주인 확인 — 고객이면 그 회원, 관리자 · 없는 회원이면 빈 값이다")
    void findCustomer() {
        given(memberRepositoryPort.findById(1L)).willReturn(Optional.of(CUSTOMER));
        given(memberRepositoryPort.findById(2L)).willReturn(Optional.of(ADMIN));
        given(memberRepositoryPort.findById(3L)).willReturn(Optional.empty());

        assertThat(processor.findCustomer(1L)).contains(CUSTOMER);
        assertThat(processor.findCustomer(2L)).isEmpty();
        assertThat(processor.findCustomer(3L)).isEmpty();
    }

    @Test
    @DisplayName("관리자 로그인 — 관리자이고 비밀번호가 맞으면 그 회원이다 (#6)")
    void adminAuthenticated() {
        given(passwordEncoder.matches("admin-password-1", "$2a$10$admin")).willReturn(true);

        assertThat(processor.authenticateAdmin(Optional.of(ADMIN), "admin-password-1")).isEqualTo(ADMIN);
    }

    @Test
    @DisplayName("관리자 로그인 — 고객 계정은 비밀번호가 맞아도 AUTH_030 이다. 비교는 똑같이 한다")
    void customerIsRejectedOnAdminLogin() {
        given(passwordEncoder.matches("mokoyuri2026!", "$2a$10$customer")).willReturn(true);

        assertAdminLoginFailed(() -> processor.authenticateAdmin(Optional.of(CUSTOMER), "mokoyuri2026!"));
        verify(passwordEncoder).matches("mokoyuri2026!", "$2a$10$customer");
    }

    @Test
    @DisplayName("관리자 로그인 — 비밀번호가 틀리면 AUTH_030, 회원이 없어도 더미 해시로 한 번 비교한 뒤 AUTH_030 이다")
    void adminWrongPasswordOrAbsent() {
        given(passwordEncoder.matches("wrong", "$2a$10$admin")).willReturn(false);

        assertAdminLoginFailed(() -> processor.authenticateAdmin(Optional.of(ADMIN), "wrong"));
        assertAdminLoginFailed(() -> processor.authenticateAdmin(Optional.empty(), "admin-password-1"));
        verify(passwordEncoder).matches("admin-password-1", DUMMY_HASH);
    }

    @Test
    @DisplayName("관리자 로그인 — 비밀번호가 null 이거나 인코더가 거절해도 AUTH_030 이다 (500 아님)")
    void adminNullOrRejectedPassword() {
        given(passwordEncoder.matches(anyString(), anyString())).willThrow(new IllegalArgumentException("password cannot be more than 72 bytes"));

        assertAdminLoginFailed(() -> processor.authenticateAdmin(Optional.of(ADMIN), "x".repeat(100)));
        assertAdminLoginFailed(() -> processor.authenticateAdmin(Optional.of(ADMIN), null));
    }

    @Test
    @DisplayName("관리자 로그인 — 64자를 넘는 비밀번호는 인코더가 맞다고 해도 AUTH_030 이다. 원문 대신 자리값으로 한 번 비교한다")
    void adminOversizedPassword() {
        String oversized = "a1" + "b".repeat(63);
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(true);

        assertAdminLoginFailed(() -> processor.authenticateAdmin(Optional.of(ADMIN), oversized));
        verify(passwordEncoder).matches(argThat(input -> !input.equals(oversized)), eq("$2a$10$admin"));
        assertThat(processor.authenticateAdmin(Optional.of(ADMIN), "a1" + "b".repeat(62))).as("64자는 받는다").isEqualTo(ADMIN);
    }

    private static void assertLoginFailed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.LOGIN_FAILED);
    }

    private static void assertAdminLoginFailed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.ADMIN_LOGIN_FAILED);
    }
}
