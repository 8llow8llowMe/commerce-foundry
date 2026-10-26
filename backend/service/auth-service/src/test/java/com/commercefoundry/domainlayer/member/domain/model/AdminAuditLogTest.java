package com.commercefoundry.domainlayer.member.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminAuditLogTest {

    private static final Instant AT = Instant.parse("2026-10-24T03:00:00Z");

    @Test
    @DisplayName("member:<양수> 만 회원 대상이다 — 목록 조건 · 0 · 앞자리 0 · 오버플로는 아니다")
    void memberTargetId() {
        assertThat(log(1L, "member:20").memberTargetId()).isEqualTo(20L);
        assertThat(log(1L, "member:4611686018427387904").memberTargetId()).isEqualTo(4611686018427387904L);
        assertThat(log(null, "members?role=ADMIN&page=0&size=20").memberTargetId()).isNull();
        assertThat(log(null, "member:").memberTargetId()).isNull();
        assertThat(log(null, "member:0").memberTargetId()).isNull();
        assertThat(log(null, "member:01").memberTargetId()).isNull();
        assertThat(log(null, "member:-1").memberTargetId()).isNull();
        assertThat(log(null, "member:abc").memberTargetId()).isNull();
        assertThat(log(null, "member:9223372036854775808").memberTargetId()).isNull();
        assertThat(log(null, null).memberTargetId()).isNull();
    }

    @Test
    @DisplayName("이름 조회 아이디는 행위자와 member 대상을 한 번만, 첫 등장 순서로 모은다")
    void referencedMemberIds() {
        List<AdminAuditLog> logs = List.of(
            log(10L, "member:20"),
            log(10L, "members?role=ALL&page=0&size=20"),
            log(null, "member:20"),
            log(0L, "member:30")
        );

        assertThat(AdminAuditLog.referencedMemberIds(logs)).containsExactly(10L, 20L, 30L);
    }

    private static AdminAuditLog log(Long actorMemberId, String target) {
        return new AdminAuditLog(1L, "ADMIN_LOGIN", actorMemberId, target, "SUCCESS", AT);
    }
}
