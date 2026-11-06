package com.commercefoundry.domainlayer.member.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAccountItem;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminAccountCreatedResponse;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAccountUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 가게 운영자 계정의 HTTP 계약 (#193). 상품을 파는 사업자의 직원 계정이다. 개발자 콘솔이 아니다.
 */
@WebMvcTest(AdminAccountAdminController.class)
@Import({
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class AdminAccountAdminControllerTest {

    private static final String CREATE = "/api/v1/admin/admins";
    private static final String PASSWORD = "/api/v1/admin/me/password";
    private static final String CREATE_BODY = """
        {"email":"staff@example.com","password":"shop-staff-2026","name":"김운영"}
        """;
    private static final String ROLE_BODY = "{\"role\":\"STAFF\"}";
    private static final String STATUS_BODY = "{\"active\":false}";
    private static final String PASSWORD_BODY = """
        {"currentPassword":"current-password1","newPassword":"next-password-2026"}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private AdminAccountUseCase adminAccountUseCase;

    @Test
    @DisplayName("ADMIN 이 운영자를 추가하면 201 이고 memberId 는 문자열이다")
    void adminCreatesAccount() {
        given(adminAccountUseCase.createAdmin(TestTokens.MEMBER_ID, "staff@example.com", "shop-staff-2026", "김운영", null))
            .willReturn(AdminAccountCreatedResponse.builder().memberId(String.valueOf(TestTokens.MEMBER_ID)).build());

        MvcTestResult result = post(CREATE, CREATE_BODY, MemberRole.ADMIN);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.memberId").isEqualTo(String.valueOf(TestTokens.MEMBER_ID));
    }

    @Test
    @DisplayName("ADMIN 이 다른 운영자를 끄면 200 이고 dataBody 는 없다")
    void adminDeactivates() {
        MvcTestResult result = patch("/api/v1/admin/admins/" + TestTokens.MEMBER_ID, null, MemberRole.ADMIN);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        verify(adminAccountUseCase).deactivateAdmin(eq(TestTokens.MEMBER_ID), eq(TestTokens.MEMBER_ID), any());
    }

    @Test
    @DisplayName("ADMIN 이 본인 비밀번호를 바꾸면 200 이다. 행위자는 토큰의 memberId 다")
    void adminChangesOwnPassword() {
        MvcTestResult result = patch(PASSWORD, PASSWORD_BODY, MemberRole.ADMIN);

        assertThat(result).hasStatusOk();
        verify(adminAccountUseCase).changePassword(eq(TestTokens.MEMBER_ID), any(), eq("current-password1"), eq("next-password-2026"));
    }

    @Test
    @DisplayName("CUSTOMER 면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
    void customerIsForbidden() {
        SecurityEnvelopeAssertions.assertEnvelope(post(CREATE, CREATE_BODY, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(adminAccountUseCase);
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER"})
    @DisplayName("운영자 추가 · 비활성화는 직원 계정 관리 권한(ADMIN_STAFF_MANAGE)이 있는 ADMIN · OWNER 가 쓴다 (#236)")
    void staffManageIsAllowed(MemberRole role) {
        assertThat(post(CREATE, CREATE_BODY, role)).hasStatus(HttpStatus.CREATED);
        assertThat(patch("/api/v1/admin/admins/7", null, role)).hasStatusOk();
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"MANAGER", "STAFF"})
    @DisplayName("매니저 · 스태프는 운영자 추가 · 비활성화가 403 · SECURITY_005 이고 유스케이스를 부르지 않는다 (#236)")
    void staffManageIsForbidden(MemberRole role) {
        SecurityEnvelopeAssertions.assertEnvelope(post(CREATE, CREATE_BODY, role).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(patch("/api/v1/admin/admins/7", null, role).getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(adminAccountUseCase);
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER", "MANAGER", "STAFF"})
    @DisplayName("본인 비밀번호 변경은 운영자 역할 전체가 쓴다 (#236)")
    void ownPasswordIsOpenToEveryOperator(MemberRole role) {
        assertThat(patch(PASSWORD, PASSWORD_BODY, role)).hasStatusOk();
        verify(adminAccountUseCase).changePassword(eq(TestTokens.MEMBER_ID), any(), eq("current-password1"), eq("next-password-2026"));
    }

    @Test
    @DisplayName("토큰이 없으면 401 · SECURITY_001 이다")
    void anonymousIsUnauthorized() {
        SecurityEnvelopeAssertions.assertEnvelope(
            mvc.post().uri(CREATE).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY).exchange().getResponse(),
            SecurityErrorCode.UNAUTHORIZED);
        verifyNoInteractions(adminAccountUseCase);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "이메일 형식 | {\"email\":\"not-an-email\",\"password\":\"shop-staff-2026\",\"name\":\"김운영\"} | AUTH_140 | email",
        "비밀번호 짧음 | {\"email\":\"staff@example.com\",\"password\":\"short1\",\"name\":\"김운영\"} | AUTH_141 | password",
        "이름 없음 | {\"email\":\"staff@example.com\",\"password\":\"shop-staff-2026\",\"name\":\"  \"} | AUTH_143 | name"
    })
    @DisplayName("추가 요청이 틀리면 400 · 필드별 코드이고 유스케이스를 부르지 않는다")
    void invalidCreate(String caseName, String body, String code, String field) {
        MvcTestResult result = post(CREATE, body, MemberRole.ADMIN);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
        verifyNoInteractions(adminAccountUseCase);
    }

    @Test
    @DisplayName("운영자 아이디가 0 이면 400 · AUTH_146 이다")
    void nonPositiveAdminId() {
        MvcTestResult result = patch("/api/v1/admin/admins/0", null, MemberRole.ADMIN);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_146");
        verifyNoInteractions(adminAccountUseCase);
    }

    @Test
    @DisplayName("운영자 추가 본문의 역할을 유스케이스에 넘긴다 (#236)")
    void createPassesRole() {
        assertThat(post(CREATE, "{\"email\":\"staff@example.com\",\"password\":\"shop-staff-2026\",\"name\":\"김운영\",\"role\":\"MANAGER\"}", MemberRole.OWNER))
            .hasStatus(HttpStatus.CREATED);
        verify(adminAccountUseCase).createAdmin(TestTokens.MEMBER_ID, "staff@example.com", "shop-staff-2026", "김운영", MemberRole.MANAGER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CUSTOMER", "manager", "OWNERS"})
    @DisplayName("운영자 추가 역할이 OWNER · MANAGER · STAFF 가 아니면 400 · AUTH_159 이다 (#236)")
    void createRejectsUnassignableRole(String role) {
        MvcTestResult result = post(CREATE,
            "{\"email\":\"staff@example.com\",\"password\":\"shop-staff-2026\",\"name\":\"김운영\",\"role\":\"%s\"}".formatted(role), MemberRole.OWNER);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_159");
        verifyNoInteractions(adminAccountUseCase);
    }

    @Nested
    @DisplayName("운영자 목록 · 역할 · 상태 (#236)")
    class StaffAccounts {

        @ParameterizedTest
        @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER"})
        @DisplayName("목록은 직원 조회 권한(ADMIN_STAFF_READ)이 있는 ADMIN · OWNER 가 본다 — 기본 page 0 · size 20, active 없음")
        void listIsAllowed(MemberRole role) {
            given(adminAccountUseCase.searchAdmins(TestTokens.MEMBER_ID, null, 0, 20)).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

            assertThat(get(CREATE, role)).hasStatusOk();
            verify(adminAccountUseCase).searchAdmins(TestTokens.MEMBER_ID, null, 0, 20);
        }

        @Test
        @DisplayName("목록의 active · page · size 를 그대로 넘기고 항목을 문자열 아이디로 준다")
        void listPassesFilters() {
            AdminAccountItem item = AdminAccountItem.builder().memberId("11").email("ma***@e***.com").name("김*니").role(MemberRole.MANAGER)
                .active(false).deactivatedAt(Instant.parse("2026-11-03T00:00:00Z")).otpEnrolled(true).createdAt(Instant.parse("2026-11-01T00:00:00Z")).build();
            given(adminAccountUseCase.searchAdmins(TestTokens.MEMBER_ID, false, 1, 5)).willReturn(new PageResponse<>(List.of(item), 1, 5, 6, 2));

            MvcTestResult result = get(CREATE + "?active=false&page=1&size=5", MemberRole.OWNER);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].memberId").isEqualTo("11");
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].active").isEqualTo(false);
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].otpEnrolled").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].deactivatedAt").isEqualTo("2026-11-03T00:00:00Z");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"page=-1, AUTH_133", "size=0, AUTH_134", "size=101, AUTH_134", "active=maybe, AUTH_198"})
        @DisplayName("목록 파라미터가 틀리면 400 · 해당 코드이고 유스케이스를 부르지 않는다")
        void invalidListParameters(String query, String code) {
            MvcTestResult result = get(CREATE + "?" + query, MemberRole.OWNER);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            verifyNoInteractions(adminAccountUseCase);
        }

        @ParameterizedTest
        @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER"})
        @DisplayName("역할 · 상태 변경은 직원 계정 관리 권한(ADMIN_STAFF_MANAGE)이 있는 ADMIN · OWNER 가 쓴다 — 200 · dataBody 없음")
        void changesAreAllowed(MemberRole role) {
            MvcTestResult roleResult = patch("/api/v1/admin/admins/7/role", ROLE_BODY, role);
            MvcTestResult statusResult = patch("/api/v1/admin/admins/7/status", STATUS_BODY, role);

            assertThat(roleResult).hasStatusOk();
            assertThat(roleResult).bodyJson().extractingPath("$.dataBody").isNull();
            assertThat(statusResult).hasStatusOk();
            verify(adminAccountUseCase).changeRole(TestTokens.MEMBER_ID, 7L, MemberRole.STAFF);
            verify(adminAccountUseCase).changeStatus(TestTokens.MEMBER_ID, 7L, false);
        }

        @Test
        @DisplayName("active=true 는 재활성화로 넘긴다")
        void reactivationIsPassed() {
            assertThat(patch("/api/v1/admin/admins/7/status", "{\"active\":true}", MemberRole.OWNER)).hasStatusOk();
            verify(adminAccountUseCase).changeStatus(TestTokens.MEMBER_ID, 7L, true);
        }

        @ParameterizedTest
        @EnumSource(value = MemberRole.class, names = {"MANAGER", "STAFF"})
        @DisplayName("매니저 · 스태프는 목록 · 역할 · 상태 변경이 403 · SECURITY_005 이고 유스케이스를 부르지 않는다 — 본문은 유효하게 보낸다")
        void nonOwnersAreForbidden(MemberRole role) {
            SecurityEnvelopeAssertions.assertEnvelope(get(CREATE, role).getResponse(), SecurityErrorCode.FORBIDDEN);
            SecurityEnvelopeAssertions.assertEnvelope(patch("/api/v1/admin/admins/7/role", ROLE_BODY, role).getResponse(), SecurityErrorCode.FORBIDDEN);
            SecurityEnvelopeAssertions.assertEnvelope(patch("/api/v1/admin/admins/7/status", STATUS_BODY, role).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(adminAccountUseCase);
        }

        @Test
        @DisplayName("고객 토큰은 목록이 403 · SECURITY_005 다")
        void customerIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(get(CREATE, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(adminAccountUseCase);
        }

        @ParameterizedTest
        @ValueSource(strings = {"{}", "{\"role\":null}", "{\"role\":\"ADMIN\"}", "{\"role\":\"CUSTOMER\"}", "{\"role\":\"staff\"}"})
        @DisplayName("역할이 없거나 OWNER · MANAGER · STAFF 가 아니면 400 · AUTH_159 이다")
        void invalidRole(String body) {
            MvcTestResult result = patch("/api/v1/admin/admins/7/role", body, MemberRole.OWNER);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_159");
            verifyNoInteractions(adminAccountUseCase);
        }

        @ParameterizedTest
        @ValueSource(strings = {"{}", "{\"active\":null}"})
        @DisplayName("사용 여부가 없으면 400 · AUTH_160 이다")
        void missingActive(String body) {
            MvcTestResult result = patch("/api/v1/admin/admins/7/status", body, MemberRole.OWNER);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_160");
            verifyNoInteractions(adminAccountUseCase);
        }

        @Test
        @DisplayName("역할 · 상태 변경의 운영자 아이디가 0 이면 400 · AUTH_146 이다")
        void nonPositiveId() {
            assertThat(patch("/api/v1/admin/admins/0/role", ROLE_BODY, MemberRole.OWNER)).bodyJson()
                .extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_146");
            assertThat(patch("/api/v1/admin/admins/0/status", STATUS_BODY, MemberRole.OWNER)).bodyJson()
                .extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_146");
            verifyNoInteractions(adminAccountUseCase);
        }
    }

    private MvcTestResult get(String uri, MemberRole role) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    private MvcTestResult post(String uri, String json, MemberRole role) {
        return mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(role))
            .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private MvcTestResult patch(String uri, String json, MemberRole role) {
        var request = mvc.patch().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(role));
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    private static String bearer(MemberRole role) {
        String token = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
        return "Bearer " + token;
    }
}
