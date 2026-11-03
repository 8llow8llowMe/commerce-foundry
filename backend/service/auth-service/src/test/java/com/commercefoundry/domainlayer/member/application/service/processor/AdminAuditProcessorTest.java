package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 관리자 로그인 실패 감사의 대상 판정 — 노린 계정이 운영자일 때만 그 memberId 를 남긴다 (#236).
 */
class AdminAuditProcessorTest {

    private final AdminAuditPort adminAuditPort = mock(AdminAuditPort.class);
    private final AdminAuditProcessor processor = new AdminAuditProcessor(adminAuditPort);

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER", "MANAGER", "STAFF"})
    @DisplayName("노린 계정이 운영자(역할 무관)면 로그인 실패의 대상으로 그 memberId 를 남긴다")
    void operatorTargetIsRecorded(MemberRole role) {
        processor.adminLoginFailed(Optional.of(member(7L, role)));

        verify(adminAuditPort).adminLoginFailed(OptionalLong.of(7L));
    }

    @Test
    @DisplayName("노린 계정이 고객이거나 없으면 대상을 남기지 않는다 — 고객 memberId 는 관리자 감사 로그에 싣지 않는다")
    void customerOrMissingTargetIsNotRecorded() {
        processor.adminLoginFailed(Optional.of(member(8L, MemberRole.CUSTOMER)));
        processor.adminLoginFailed(Optional.empty());

        verify(adminAuditPort, times(2)).adminLoginFailed(OptionalLong.empty());
    }

    private static Member member(long id, MemberRole role) {
        return Member.restore(id, id + "@example.com", "hash", "운영", null, role, 0L);
    }
}
