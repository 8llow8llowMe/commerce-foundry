package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 인가 스모크 — security-core Resource Server 가 이 서비스에 실제로 걸렸는지 본다 ({@code security-guide.md} §5-3).
 * 애노테이션은 {@link OperationsServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다.
 */
class OperationsServiceSecuritySmokeTest extends ResourceServerSmokeTestSupport {

    @Test
    void campaignPreviewUsesActualResourceServer() throws Exception {
        String path = "/api/v1/admin/campaigns/recipient-previews";
        assertThat(perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")).getStatus()).isEqualTo(401);
        assertThat(perform(post(path).header("Authorization", bearer(MemberRole.CUSTOMER))
            .contentType(MediaType.APPLICATION_JSON).content("{}")).getStatus()).isEqualTo(403);
        assertThat(perform(post(path).header("Authorization", bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content("{}")).getStatus()).isEqualTo(400);
    }

    /** 감사 선언(#163)을 아직 붙이지 않은 어드민 쓰기 핸들러 — 맡은 PR 에서 선언을 붙이고 지운다 ({@code security-guide.md} §11-4). */
    @Override
    protected Map<String, String> auditDeclarationPending() {
        return Map.ofEntries(
            Map.entry("NotificationAdminController#markRead", "#163 PR-E (면제 대상 — 알림 읽음)"),
            Map.entry("NotificationAdminController#markAllRead", "#163 PR-E (면제 대상 — 알림 읽음)"),
            Map.entry("OnboardingAdminController#updateTour", "#163 PR-E (면제 대상 — 본인 온보딩 투어 기록)")
        );
    }
}
