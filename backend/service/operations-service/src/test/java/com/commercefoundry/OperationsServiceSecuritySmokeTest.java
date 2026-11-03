package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport;
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
}
