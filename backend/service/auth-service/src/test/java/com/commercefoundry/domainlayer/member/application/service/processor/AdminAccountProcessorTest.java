package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.principal.MemberRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminAccountProcessorTest {

    private static final Instant AT = Instant.parse("2026-10-26T01:00:00Z");
    private static final Member OWNER = admin(1L);
    private static final Member STAFF = admin(2L);

    private final MemberRepositoryPort memberRepositoryPort = mock(MemberRepositoryPort.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final SnowflakeIdGenerator snowflakeIdGenerator = mock(SnowflakeIdGenerator.class);
    private AdminAccountProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new AdminAccountProcessor(memberRepositoryPort, passwordEncoder, snowflakeIdGenerator);
        given(memberRepositoryPort.save(any())).willAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("이메일이 없으면 관리자 계정을 만든다 — 고객으로 올리지 않는다")
    void createsAdmin() {
        given(memberRepositoryPort.existsByEmail("staff@example.com")).willReturn(false);
        given(snowflakeIdGenerator.generateId()).willReturn(9L);

        Member created = processor.create("staff@example.com", "hash", "  김운영  ");

        assertThat(created.id()).isEqualTo(9L);
        assertThat(created.email()).isEqualTo("staff@example.com");
        assertThat(created.name()).isEqualTo("김운영");
        assertThat(created.role()).isEqualTo(MemberRole.ADMIN);
        assertThat(created.isActiveAdmin()).isTrue();
        assertThat(created.phone()).isNull();
    }

    @Test
    @DisplayName("이미 있는 이메일(고객 포함)은 AUTH_002 이고 저장하지 않는다")
    void duplicateEmail() {
        given(memberRepositoryPort.existsByEmail("staff@example.com")).willReturn(true);

        assertCode(MemberErrorCode.DUPLICATE_EMAIL, () -> processor.create("staff@example.com", "hash", "김운영"));
        verify(memberRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("활성 운영자가 둘 이상이면 대상만 끈다")
    void deactivatesWhenAnotherAdminRemains() {
        given(memberRepositoryPort.findActiveAdminsForUpdate()).willReturn(List.of(OWNER, STAFF));

        processor.deactivate(STAFF.id(), AT);

        verify(memberRepositoryPort).save(argThat(saved -> saved.id() == STAFF.id() && AT.equals(saved.deactivatedAt())));
    }

    @Test
    @DisplayName("마지막 활성 운영자는 AUTH_031 이고 저장하지 않는다")
    void lastAdminIsRejected() {
        given(memberRepositoryPort.findActiveAdminsForUpdate()).willReturn(List.of(OWNER));

        assertCode(MemberErrorCode.LAST_ACTIVE_ADMIN, () -> processor.deactivate(OWNER.id(), AT));
        verify(memberRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("없거나 이미 꺼진 계정은 AUTH_033 이다")
    void missingAdminIsNotFound() {
        given(memberRepositoryPort.findActiveAdminsForUpdate()).willReturn(List.of(OWNER));

        assertCode(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND, () -> processor.deactivate(99L, AT));
        verify(memberRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("활성 운영자의 비밀번호 해시만 바꾼다")
    void changesPassword() {
        given(memberRepositoryPort.findByIdForUpdate(OWNER.id())).willReturn(Optional.of(OWNER));

        processor.changePassword(OWNER.id(), "new-hash");

        verify(memberRepositoryPort).save(argThat(saved -> saved.id() == OWNER.id()
            && "new-hash".equals(saved.passwordHash())
            && saved.deactivatedAt() == null));
    }

    @Test
    @DisplayName("꺼진 운영자의 비밀번호는 바꾸지 않는다 — AUTH_033")
    void deactivatedPasswordChangeIsNotFound() {
        Member deactivated = OWNER.deactivate(AT);
        given(memberRepositoryPort.findByIdForUpdate(deactivated.id())).willReturn(Optional.of(deactivated));

        assertCode(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND, () -> processor.changePassword(deactivated.id(), "new-hash"));
        verify(memberRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("인코더가 거절하면 현재 비밀번호는 틀린 것이다")
    void encoderRejectionIsMismatch() {
        given(passwordEncoder.matches(any(), any())).willThrow(new IllegalArgumentException("rejected"));

        assertThat(processor.passwordMatches(OWNER, "x".repeat(100))).isFalse();
    }

    private static Member admin(long id) {
        return Member.restore(id, id + "@example.com", "hash-" + id, "운영", null, MemberRole.ADMIN, 0L);
    }

    private static void assertCode(MemberErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(code);
    }
}
