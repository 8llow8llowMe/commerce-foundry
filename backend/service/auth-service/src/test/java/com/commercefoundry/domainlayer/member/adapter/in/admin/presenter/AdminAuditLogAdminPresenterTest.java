package com.commercefoundry.domainlayer.member.adapter.in.admin.presenter;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAuditLogAdminItem;
import com.commercefoundry.domainlayer.member.domain.model.AdminAuditLog;
import com.commercefoundry.persistence.dto.PageResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

class AdminAuditLogAdminPresenterTest {

    private static final Instant AT = Instant.parse("2026-10-24T03:00:00Z");

    private final AdminAuditLogAdminPresenter presenter = new AdminAuditLogAdminPresenter();

    @Test
    @DisplayName("이름이 있으면 마스킹하고, 없거나 회원 대상이 아니면 null 이다 — 아이디는 문자열")
    void masksNames() {
        Map<Long, String> names = new HashMap<>();
        names.put(10L, "홍길동");
        names.put(20L, null);
        names.put(30L, "   ");
        List<AdminAuditLog> logs = List.of(
            new AdminAuditLog(1L, "ADMIN_LOGIN", 10L, "member:20", "SUCCESS", AT),
            new AdminAuditLog(2L, "MEMBER_SEARCH", 30L, "members?role=ALL&page=0&size=20", "SUCCESS", AT),
            new AdminAuditLog(3L, "ADMIN_LOGIN", null, "member:99", "FAILURE", AT)
        );

        PageResponse<AdminAuditLogAdminItem> response = presenter.toPageResponse(new PageImpl<>(logs), names);

        assertThat(response.contents()).containsExactly(
            AdminAuditLogAdminItem.builder().auditLogId("1").action("ADMIN_LOGIN").actorMemberId("10").actorDisplayName("홍*동")
                .target("member:20").targetDisplayName(null).result("SUCCESS").createdAt(AT).build(),
            AdminAuditLogAdminItem.builder().auditLogId("2").action("MEMBER_SEARCH").actorMemberId("30").actorDisplayName(null)
                .target("members?role=ALL&page=0&size=20").targetDisplayName(null).result("SUCCESS").createdAt(AT).build(),
            AdminAuditLogAdminItem.builder().auditLogId("3").action("ADMIN_LOGIN").actorMemberId(null).actorDisplayName(null)
                .target("member:99").targetDisplayName(null).result("FAILURE").createdAt(AT).build()
        );
    }
}
