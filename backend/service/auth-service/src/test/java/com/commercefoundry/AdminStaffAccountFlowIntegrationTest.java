package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.port.in.AdminSeedUseCase;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditLogRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AdminAuditLog;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.support.InMemoryAdminAccessCutoffStore;
import com.commercefoundry.support.InMemoryAuthStoresConfig;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 운영자 계정 관리 흐름 (#236) — 시드 → 역할 지정 추가 → 목록 → 강등 → 다시 로그인한 토큰의 역할 → 정지 → 해제를 실제 빈 구성(보안 체인 · Facade · Processor ·
 * 어댑터 · H2)으로 본다. 역할 발급 플래그를 켠 컨텍스트다(운영 기본은 꺼짐). Redis 포트만 인메모리 스텁이다.
 */
@SpringBootTest(properties = {
    "auth.operator-roles.issuance-enabled=true",
    // 다른 컨텍스트와 H2 메모리 DB 를 나누지 않는다 — 같은 이름이면 이 컨텍스트의 create-drop 이 다른 컨텍스트의 행을 지운다
    "spring.datasource.url=jdbc:h2:mem:staff-account;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
        + "INIT=CREATE SCHEMA IF NOT EXISTS auth"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(InMemoryAuthStoresConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminStaffAccountFlowIntegrationTest {

    private static final String ADMINS = "/api/v1/admin/admins";
    private static final String PASSWORD = "staff-flow-password-1";

    @Autowired
    private MockMvcTester mvc;
    @Autowired
    private AdminSeedUseCase seeds;
    @Autowired
    private MemberRepositoryPort members;
    @Autowired
    private AdminAuditLogRepositoryPort audits;
    @Autowired
    private InMemoryAdminAccessCutoffStore cutoffs;

    @AfterEach
    void clearCutoffs() {
        cutoffs.clear();
    }

    @Test
    @DisplayName("추가(MANAGER) → 목록 → 강등(STAFF) → 다시 로그인하면 role=STAFF → 정지하면 로그인 거절 → 해제하면 같은 비밀번호로 로그인")
    void staffAccountLifecycle() throws Exception {
        Member owner = seed();
        String ownerJwt = jwt(owner.id(), MemberRole.ADMIN);
        String email = uniqueEmail();

        MvcTestResult created = mvc.post().uri(ADMINS).header(HttpHeaders.AUTHORIZATION, ownerJwt).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\",\"name\":\"김매니저\",\"role\":\"MANAGER\"}".formatted(email, PASSWORD)).exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        long staffId = Long.parseLong(JsonPath.read(body(created), "$.dataBody.memberId"));
        assertThat(roleClaim(login(email))).isEqualTo("MANAGER");

        MvcTestResult list = mvc.get().uri(ADMINS + "?active=true&size=100").header(HttpHeaders.AUTHORIZATION, ownerJwt).exchange();
        assertThat(list).hasStatusOk();
        List<Map<String, Object>> rows = JsonPath.read(body(list), "$.dataBody.contents[?(@.memberId == '" + staffId + "')]");
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("role", "MANAGER").containsEntry("active", true).containsEntry("otpEnrolled", false);
            assertThat(row.get("name")).isEqualTo("김*저");
            assertThat(row.get("deactivatedAt")).isNull();
        });
        assertThat(body(list)).doesNotContain(email);
        assertAudit("ADMIN_SEARCH", owner.id(), "admins?active=true&page=0&size=100", "SUCCESS");

        assertThat(patch(ADMINS + "/" + staffId + "/role", "{\"role\":\"STAFF\"}", ownerJwt)).hasStatusOk();
        assertThat(members.findById(staffId).orElseThrow().role()).isEqualTo(MemberRole.STAFF);
        assertThat(cutoffs.findCutoff(staffId)).isPresent();
        assertAudit("ADMIN_ROLE_CHANGE", owner.id(), "member:" + staffId, "SUCCESS");
        assertThat(roleClaim(login(email))).isEqualTo("STAFF");

        cutoffs.clear();
        assertThat(patch(ADMINS + "/" + staffId + "/status", "{\"active\":false}", ownerJwt)).hasStatusOk();
        assertThat(members.findById(staffId).orElseThrow().isActiveOperator()).isFalse();
        assertThat(cutoffs.findCutoff(staffId)).isPresent();
        assertCode(login(email), HttpStatus.BAD_REQUEST, "AUTH_030");
        assertThat(patch(ADMINS + "/" + staffId + "/status", "{\"active\":false}", ownerJwt)).hasStatusOk();

        MvcTestResult inactive = mvc.get().uri(ADMINS + "?active=false&size=100").header(HttpHeaders.AUTHORIZATION, ownerJwt).exchange();
        assertThat(JsonPath.<List<String>>read(body(inactive), "$.dataBody.contents[*].memberId")).contains(String.valueOf(staffId));

        cutoffs.clear();
        assertThat(patch(ADMINS + "/" + staffId + "/status", "{\"active\":true}", ownerJwt)).hasStatusOk();
        assertThat(members.findById(staffId).orElseThrow().isActiveOperator()).isTrue();
        assertThat(cutoffs.findCutoff(staffId)).isEmpty();
        assertAudit("ADMIN_REACTIVATE", owner.id(), "member:" + staffId, "SUCCESS");
        assertThat(roleClaim(login(email))).isEqualTo("STAFF");
    }

    @Test
    @DisplayName("역할 저장 뒤 컷오프가 실패하면 503 AUTH_013 · 감사 FAILURE 이고, 같은 요청을 다시 보내면 컷오프가 써진다")
    void roleChangeCutoffFailureRecoversOnRetry() throws Exception {
        Member owner = seed();
        Member target = seed();
        String ownerJwt = jwt(owner.id(), MemberRole.ADMIN);
        cutoffs.failNextWrite();

        assertCode(patch(ADMINS + "/" + target.id() + "/role", "{\"role\":\"MANAGER\"}", ownerJwt), HttpStatus.SERVICE_UNAVAILABLE, "AUTH_013");
        assertThat(members.findById(target.id()).orElseThrow().role()).isEqualTo(MemberRole.MANAGER);
        assertThat(cutoffs.findCutoff(target.id())).isEmpty();
        assertAudit("ADMIN_ROLE_CHANGE", owner.id(), "member:" + target.id(), "FAILURE");

        // 컷오프가 없어 강등된 계정의 옛 ADMIN 토큰이 아직 메서드 보안을 통과해도, 운영자 추가는 행위자를 DB 로 다시 판정해 거절한다
        String staleJwt = jwt(target.id(), MemberRole.ADMIN);
        MvcTestResult staleCreate = mvc.post().uri(ADMINS).header(HttpHeaders.AUTHORIZATION, staleJwt).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\",\"name\":\"김직원\"}".formatted(uniqueEmail(), PASSWORD)).exchange();
        assertCode(staleCreate, HttpStatus.FORBIDDEN, "AUTH_046");

        assertThat(patch(ADMINS + "/" + target.id() + "/role", "{\"role\":\"MANAGER\"}", ownerJwt)).hasStatusOk();
        assertThat(cutoffs.findCutoff(target.id())).isPresent();
    }

    @Test
    @DisplayName("본인 역할 · 상태 변경은 400 AUTH_047 이고 실패 감사를 남긴다")
    void selfManagementIsRejected() throws Exception {
        Member owner = seed();
        String ownerJwt = jwt(owner.id(), MemberRole.ADMIN);

        assertCode(patch(ADMINS + "/" + owner.id() + "/role", "{\"role\":\"STAFF\"}", ownerJwt), HttpStatus.BAD_REQUEST, "AUTH_047");
        assertCode(patch(ADMINS + "/" + owner.id() + "/status", "{\"active\":false}", ownerJwt), HttpStatus.BAD_REQUEST, "AUTH_047");
        assertThat(members.findById(owner.id()).orElseThrow()).satisfies(member -> {
            assertThat(member.role()).isEqualTo(MemberRole.ADMIN);
            assertThat(member.isActiveOperator()).isTrue();
        });
        assertAudit("ADMIN_ROLE_CHANGE", owner.id(), "member:" + owner.id(), "FAILURE");
        assertAudit("ADMIN_DEACTIVATE", owner.id(), "member:" + owner.id(), "FAILURE");
    }

    private Member seed() {
        String email = uniqueEmail();
        seeds.seed(new AdminSeedCommand(email, PASSWORD, "김대표"));
        return members.findByEmail(email).orElseThrow();
    }

    private MvcTestResult login(String email) {
        return mvc.post().uri("/api/v1/auth/admin/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)).exchange();
    }

    private MvcTestResult patch(String uri, String json, String bearer) {
        return mvc.patch().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private void assertAudit(String action, long actor, String target, String result) {
        List<AdminAuditLog> rows = audits.findPage(action, actor, target, null, 0, 100).getContent();
        assertThat(rows).singleElement().satisfies(row -> assertThat(row.result()).isEqualTo(result));
    }

    private static String roleClaim(MvcTestResult login) throws Exception {
        assertThat(login).hasStatusOk();
        return SignedJWT.parse(JsonPath.read(body(login), "$.dataBody.accessToken")).getJWTClaimsSet().getStringClaim(MemberRole.CLAIM_NAME);
    }

    private static void assertCode(MvcTestResult result, HttpStatus status, String code) throws Exception {
        assertThat(result).hasStatus(status);
        assertThat(JsonPath.<String>read(body(result), "$.dataHeader.resultCode")).isEqualTo(code);
    }

    private static String jwt(long memberId, MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(memberId, role).tokenValue();
    }

    private static String body(MvcTestResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String uniqueEmail() {
        return "staff-flow-" + UUID.randomUUID() + "@example.com";
    }
}
