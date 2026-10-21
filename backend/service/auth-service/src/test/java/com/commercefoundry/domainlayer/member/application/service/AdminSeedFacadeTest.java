package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AdminSeedOutcome;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminSeedProcessor;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 순서만 본다 — 규칙 판정 → 해시(트랜잭션 밖) → 저장. 규칙 자체는 {@code AdminSeedProcessorTest} 가 본다.
 */
class AdminSeedFacadeTest {

    private static final AdminSeedCommand RAW = new AdminSeedCommand(" Admin@Example.com ", "admin-password-1", null);
    private static final AdminSeedCommand VALID = new AdminSeedCommand("admin@example.com", "admin-password-1", "관리자");

    private final AdminSeedProcessor processor = mock(AdminSeedProcessor.class);
    private final AdminSeedFacade facade = new AdminSeedFacade(processor);

    @Test
    @DisplayName("설정이 비었으면 해시 · 저장 없이 NOT_CONFIGURED 다")
    void notConfigured() {
        given(processor.validate(RAW)).willReturn(Optional.empty());

        assertThat(facade.seed(RAW)).isEqualTo(AdminSeedOutcome.NOT_CONFIGURED);
        verify(processor, never()).hashPassword(anyString());
    }

    @Test
    @DisplayName("판정 → 해시 → 저장 순서이고 정규화한 값으로 저장한다")
    void created() {
        given(processor.validate(RAW)).willReturn(Optional.of(VALID));
        given(processor.hashPassword("admin-password-1")).willReturn("$2a$10$hash");
        given(processor.createIfAbsent("admin@example.com", "$2a$10$hash", "관리자")).willReturn(AdminSeedOutcome.CREATED);

        assertThat(facade.seed(RAW)).isEqualTo(AdminSeedOutcome.CREATED);
        InOrder order = inOrder(processor);
        order.verify(processor).validate(RAW);
        order.verify(processor).hashPassword("admin-password-1");
        order.verify(processor).createIfAbsent("admin@example.com", "$2a$10$hash", "관리자");
    }

    @Test
    @DisplayName("규칙 위반(IllegalStateException)은 그대로 나가 기동을 멈춘다 — 해시하지 않는다")
    void invalidStopsStartup() {
        given(processor.validate(RAW)).willThrow(new IllegalStateException("rule"));

        assertThatThrownBy(() -> facade.seed(RAW)).isInstanceOf(IllegalStateException.class);
        verify(processor, never()).hashPassword(anyString());
    }

    @Test
    @DisplayName("조회 뒤 같은 이메일이 먼저 저장됐으면(유니크 키) 건너뛴다 — 기동은 계속된다")
    void createdConcurrently() {
        given(processor.validate(RAW)).willReturn(Optional.of(VALID));
        given(processor.hashPassword("admin-password-1")).willReturn("$2a$10$hash");
        given(processor.createIfAbsent("admin@example.com", "$2a$10$hash", "관리자")).willThrow(new MemberException(MemberErrorCode.DUPLICATE_EMAIL));

        assertThat(facade.seed(RAW)).isEqualTo(AdminSeedOutcome.CREATED_CONCURRENTLY);
    }

    @Test
    @DisplayName("다른 도메인 오류는 그대로 던진다")
    void otherErrorPropagates() {
        given(processor.validate(RAW)).willReturn(Optional.of(VALID));
        given(processor.hashPassword("admin-password-1")).willReturn("$2a$10$hash");
        given(processor.createIfAbsent("admin@example.com", "$2a$10$hash", "관리자"))
            .willThrow(new MemberException(MemberErrorCode.MEMBER_UPDATE_CONFLICT));

        assertThatThrownBy(() -> facade.seed(RAW)).isInstanceOf(MemberException.class);
    }
}
