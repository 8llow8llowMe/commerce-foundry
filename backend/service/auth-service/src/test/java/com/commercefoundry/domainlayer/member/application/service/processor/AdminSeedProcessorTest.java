package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.info.AdminSeedOutcome;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.principal.MemberRole;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminSeedProcessorTest {

    private static final String EMAIL = "admin@example.com";

    private final MemberRepositoryPort memberRepositoryPort = mock(MemberRepositoryPort.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final SnowflakeIdGenerator snowflakeIdGenerator = mock(SnowflakeIdGenerator.class);
    private final AdminSeedProcessor processor = new AdminSeedProcessor(memberRepositoryPort, passwordEncoder, snowflakeIdGenerator);

    private static final String PASSWORD = "admin-password-1";

    @ParameterizedTest(name = "email=[{0}] password=[{1}]")
    @CsvSource(value = {"NULL, NULL", "'', ''", "'  ', '　'"}, nullValues = "NULL")
    @DisplayName("이메일 · 비밀번호가 모두 비면(전각 공백 포함) 빈 값이다 — 시드하지 않는다")
    void notConfigured(String email, String password) {
        assertThat(processor.validate(new AdminSeedCommand(email, password, null))).isEmpty();
    }

    @Test
    @DisplayName("둘 중 하나만 있으면 기동을 멈춘다 — 메시지에 값을 싣지 않는다")
    void onlyOneConfigured() {
        assertThatThrownBy(() -> processor.validate(new AdminSeedCommand(EMAIL, null, null)))
            .isInstanceOf(IllegalStateException.class).message().doesNotContain(EMAIL);
        assertThatThrownBy(() -> processor.validate(new AdminSeedCommand(null, PASSWORD, null)))
            .isInstanceOf(IllegalStateException.class).message().doesNotContain(PASSWORD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short-pw-1", "onlyletterspassword", "123456789012345", "has space pw 123", "한글비밀번호12345678"})
    @DisplayName("비밀번호가 규칙(12~64자, 영문 · 숫자 포함 공백 없는 ASCII) 밖이면 기동을 멈춘다 — 메시지에 값을 싣지 않는다")
    void passwordRule(String password) {
        assertThatThrownBy(() -> processor.validate(new AdminSeedCommand(EMAIL, password, null)))
            .isInstanceOf(IllegalStateException.class).message().doesNotContain(password);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "admin@localhost", "ad min@example.com"})
    @DisplayName("이메일 형식이 틀리면 기동을 멈춘다")
    void emailFormat(String email) {
        assertThatThrownBy(() -> processor.validate(new AdminSeedCommand(email, PASSWORD, null))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("비밀번호 64자 초과 · 이메일 254자 초과 · 이름 50자 초과면 기동을 멈춘다")
    void lengths() {
        assertThatThrownBy(() -> processor.validate(new AdminSeedCommand(EMAIL, "a1" + "b".repeat(63), null))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> processor.validate(new AdminSeedCommand("a".repeat(243) + "@example.com", PASSWORD, null)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> processor.validate(new AdminSeedCommand(EMAIL, PASSWORD, "가".repeat(51)))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("규칙을 지나면 소문자 이메일 · 앞뒤 공백 없는 이름으로 정규화한다")
    void normalizes() {
        assertThat(processor.validate(new AdminSeedCommand(" Admin@Example.com ", PASSWORD, " 운영자 ")))
            .hasValueSatisfying(seed -> assertThat(seed).extracting(AdminSeedCommand::email, AdminSeedCommand::rawPassword, AdminSeedCommand::name)
                .containsExactly(EMAIL, PASSWORD, "운영자"));
    }

    @ParameterizedTest(name = "name=[{0}]")
    @CsvSource(value = {"NULL", "''", "'　'"}, nullValues = "NULL")
    @DisplayName("이름이 비면(전각 공백 포함) '관리자' 다")
    void defaultName(String name) {
        assertThat(processor.validate(new AdminSeedCommand(EMAIL, PASSWORD, name)))
            .hasValueSatisfying(seed -> assertThat(seed.name()).isEqualTo(AdminSeedProcessor.DEFAULT_NAME));
    }

    @Test
    @DisplayName("같은 이메일이 없으면 Snowflake 아이디 · ADMIN · 전화 없음으로 만든다")
    void created() {
        given(memberRepositoryPort.findByEmail(EMAIL)).willReturn(Optional.empty());
        given(snowflakeIdGenerator.generateId()).willReturn(42L);
        given(memberRepositoryPort.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        assertThat(processor.createIfAbsent(EMAIL, "$2a$10$hash", "관리자")).isEqualTo(AdminSeedOutcome.CREATED);

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepositoryPort).save(saved.capture());
        assertThat(saved.getValue()).extracting(Member::id, Member::email, Member::passwordHash, Member::name, Member::phone, Member::role)
            .containsExactly(42L, EMAIL, "$2a$10$hash", "관리자", null, MemberRole.ADMIN);
    }

    @Test
    @DisplayName("같은 이메일의 관리자가 있으면 비밀번호를 덮어쓰지 않는다 — 저장하지 않는다(멱등)")
    void alreadyAdmin() {
        given(memberRepositoryPort.findByEmail(EMAIL))
            .willReturn(Optional.of(Member.restore(1L, EMAIL, "$2a$10$old", "관리자", null, MemberRole.ADMIN, 3L)));

        assertThat(processor.createIfAbsent(EMAIL, "$2a$10$new", "관리자")).isEqualTo(AdminSeedOutcome.ALREADY_ADMIN);
        verify(memberRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("같은 이메일의 고객이 있으면 관리자로 승격하지 않는다 — 저장하지 않는다")
    void customerExists() {
        given(memberRepositoryPort.findByEmail(EMAIL))
            .willReturn(Optional.of(Member.restore(1L, EMAIL, "$2a$10$old", "홍길동", null, MemberRole.CUSTOMER, 0L)));

        assertThat(processor.createIfAbsent(EMAIL, "$2a$10$new", "관리자")).isEqualTo(AdminSeedOutcome.CUSTOMER_EXISTS);
        verify(memberRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("해시는 인코더에 맡긴다")
    void hash() {
        given(passwordEncoder.encode("admin-password-1")).willReturn("$2a$10$hash");

        assertThat(processor.hashPassword("admin-password-1")).isEqualTo("$2a$10$hash");
    }
}
