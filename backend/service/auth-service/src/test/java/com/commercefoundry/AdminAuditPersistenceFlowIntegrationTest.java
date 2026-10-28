package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.AdminAuditLogRepositoryAdapter;
import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.port.in.AdminSeedUseCase;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditPort;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.support.InMemoryAuthStoresConfig;
import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 관리자 감사 영속화 흐름 (#192) — 관리자 로그인 성공 · 실패와 어드민 회원 조회가 {@code auth.admin_audit_log} 에 남고 감사 조회 API 로 보이는지,
 * 그리고 {@code admin.audit} 로그 한 줄도 그대로 남는지를 실제 빈 구성(보안 체인 · Facade · 어댑터 · H2)으로 본다.
 * 애노테이션은 {@link AuthServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다. H2 는 컨텍스트 동안 살아 있으므로 관리자 아이디로 걸러 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(InMemoryAuthStoresConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class AdminAuditPersistenceFlowIntegrationTest {

    private static final String ADMIN_LOGIN = "/api/v1/auth/admin/login";
    private static final String AUDIT_LOGS = "/api/v1/admin/audit-logs";
    private static final String PASSWORD = "admin-password-1";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AdminSeedUseCase adminSeedUseCase;

    @Autowired
    private MemberRepositoryPort memberRepositoryPort;

    @Autowired
    private AdminAuditPort adminAuditPort;

    @Test
    @DisplayName("감사 포트 구현은 DB 어댑터 하나다 — 로그 한 줄을 먼저 남기고 행을 더한다")
    void portIsDatabaseAdapter() {
        assertThat(adminAuditPort).isInstanceOf(AdminAuditLogRepositoryAdapter.class);
    }

    @Test
    @DisplayName("관리자 로그인 성공 · 실패 · 회원 조회가 감사 테이블에 남고 감사 조회 API 로 보인다 — 로그 한 줄도 그대로 남는다")
    void recordsAndLists(CapturedOutput output) {
        String email = "audit-flow-" + UUID.randomUUID() + "@example.com";
        adminSeedUseCase.seed(new AdminSeedCommand(email, PASSWORD, null));
        long adminId = memberRepositoryPort.findByEmail(email).orElseThrow().id();

        String token = "Bearer " + JsonPath.read(content(login(email, PASSWORD)), "$.dataBody.accessToken");
        login(email, "wrong-password-9");
        assertThat(mvc.get().uri("/api/v1/admin/members?page=0&size=5").header(HttpHeaders.AUTHORIZATION, token).exchange()).hasStatusOk();

        MvcTestResult byActor = mvc.get().uri(AUDIT_LOGS + "?actorMemberId=" + adminId).header(HttpHeaders.AUTHORIZATION, token).exchange();
        assertThat(byActor).hasStatusOk();
        List<Map<String, Object>> actorRows = JsonPath.read(content(byActor), "$.dataBody.contents");
        assertThat(actorRows).extracting(row -> row.get("action")).containsExactly("MEMBER_SEARCH", "ADMIN_LOGIN");
        assertThat(actorRows.getFirst()).containsEntry("target", "members?role=ALL&page=0&size=5").containsEntry("result", "SUCCESS")
            .containsEntry("actorMemberId", String.valueOf(adminId));

        MvcTestResult logins = mvc.get().uri(AUDIT_LOGS + "?action=ADMIN_LOGIN&size=100").header(HttpHeaders.AUTHORIZATION, token).exchange();
        List<Map<String, Object>> loginRows = JsonPath.read(content(logins), "$.dataBody.contents");
        assertThat(loginRows).filteredOn(row -> ("member:" + adminId).equals(row.get("target")))
            .extracting(row -> row.get("result"), row -> row.get("actorMemberId"))
            .containsExactly(
                tuple("FAILURE", null),
                tuple("SUCCESS", String.valueOf(adminId))
            );

        assertThat(output.getOut())
            .contains("action=ADMIN_LOGIN actorMemberId=" + adminId + " target=member:" + adminId + " result=SUCCESS")
            .contains("action=ADMIN_LOGIN actorMemberId=- target=member:" + adminId + " result=FAILURE");
    }

    private MvcTestResult login(String email, String password) {
        return mvc.post().uri(ADMIN_LOGIN).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)).exchange();
    }

    private static String content(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
