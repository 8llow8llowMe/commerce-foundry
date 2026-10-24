package com.commercefoundry.domainlayer.member.adapter.out.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.application.model.AdminAuditRecord;
import com.commercefoundry.security.principal.MemberRole;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 감사 한 줄의 형식 — 로그 수집기가 이 모양으로 파싱한다 ({@code security-guide.md} §11-4). 칸 순서 · 이름을 바꾸면 이 테스트가 깨져야 한다.
 * 칸을 채우는 규칙은 {@link AdminAuditRecord} 의 팩토리다 — DB 행도 같은 값을 남긴다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminAuditLogAdapterTest {

    private final AdminAuditLogAdapter adapter = new AdminAuditLogAdapter();

    @Test
    @DisplayName("관리자 로그인 성공 · 실패(대상 있음 · 없음)는 action · actorMemberId · target · result 네 칸이다")
    void adminLogin(CapturedOutput output) {
        adapter.write(AdminAuditRecord.adminLoginSucceeded(9L));
        adapter.write(AdminAuditRecord.adminLoginFailed(OptionalLong.of(9L)));
        adapter.write(AdminAuditRecord.adminLoginFailed(OptionalLong.empty()));

        assertThat(output.getOut())
            .contains("admin.audit")
            .contains("action=ADMIN_LOGIN actorMemberId=9 target=member:9 result=SUCCESS")
            .contains("action=ADMIN_LOGIN actorMemberId=- target=member:9 result=FAILURE")
            .contains("action=ADMIN_LOGIN actorMemberId=- target=- result=FAILURE");
    }

    @Test
    @DisplayName("회원 목록 조회는 조건 · 페이지를 대상으로 남긴다 — 역할이 없으면 ALL")
    void membersSearched(CapturedOutput output) {
        adapter.write(AdminAuditRecord.membersSearched(9L, null, 0, 20));
        adapter.write(AdminAuditRecord.membersSearched(9L, MemberRole.ADMIN, 1, 50));

        assertThat(output.getOut())
            .contains("action=MEMBER_SEARCH actorMemberId=9 target=members?role=ALL&page=0&size=20 result=SUCCESS")
            .contains("action=MEMBER_SEARCH actorMemberId=9 target=members?role=ADMIN&page=1&size=50 result=SUCCESS");
    }
}
