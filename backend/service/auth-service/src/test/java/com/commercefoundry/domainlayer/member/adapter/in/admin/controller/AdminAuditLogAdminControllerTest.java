package com.commercefoundry.domainlayer.member.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAuditLogAdminItem;
import com.commercefoundry.domainlayer.member.application.command.AdminAuditLogSearchCommand;
import com.commercefoundry.domainlayer.member.application.model.AdminAuditAction;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAuditLogAdminUseCase;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 어드민 감사 로그 조회의 HTTP 계약 — 권한(URL 규칙 + {@code hasRole('ADMIN')}), 조건 · 페이지 파라미터 검증 코드, 응답 모양.
 * 슬라이스라 보안 구성을 직접 import 하고, 테스트 키로 서명한 실제 access 토큰을 보낸다.
 */
@WebMvcTest(AdminAuditLogAdminController.class)
@Import({
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class AdminAuditLogAdminControllerTest {

    private static final String PATH = "/api/v1/admin/audit-logs";
    private static final PageResponse<AdminAuditLogAdminItem> PAGE = new PageResponse<>(List.of(AdminAuditLogAdminItem.builder()
        .auditLogId("4611686018427387906").action("ADMIN_LOGIN").actorMemberId(null).target("member:4611686018427387904").result("FAILURE")
        .createdAt(Instant.parse("2026-10-24T03:00:00Z")).build()), 0, 20, 1, 1);

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private AdminAuditLogAdminUseCase adminAuditLogAdminUseCase;

    private MvcTestResult get(String uri, MemberRole role) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.issuer(Instant.now())
            .issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue()).exchange();
    }

    @Test
    @DisplayName("ADMIN 이면 200 PageResponse — 기본 조건 없음 · page 0 · size 20, 아이디는 문자열이고 모르는 행위자는 null 이다")
    void adminIsAllowed() {
        given(adminAuditLogAdminUseCase.searchAuditLogs(any())).willReturn(PAGE);

        MvcTestResult result = get(PATH, MemberRole.ADMIN);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].auditLogId").isEqualTo("4611686018427387906");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].action").isEqualTo("ADMIN_LOGIN");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].actorMemberId").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].target").isEqualTo("member:4611686018427387904");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].result").isEqualTo("FAILURE");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].createdAt").isEqualTo("2026-10-24T03:00:00Z");
        verify(adminAuditLogAdminUseCase).searchAuditLogs(new AdminAuditLogSearchCommand(null, null, 0, 20));
    }

    @Test
    @DisplayName("행위 · 관리자 · 페이지를 command 로 넘긴다")
    void passesFilters() {
        given(adminAuditLogAdminUseCase.searchAuditLogs(any())).willReturn(new PageResponse<>(List.of(), 1, 50, 0, 0));

        assertThat(get(PATH + "?action=MEMBER_SEARCH&actorMemberId=7&page=1&size=50", MemberRole.ADMIN)).hasStatusOk();

        verify(adminAuditLogAdminUseCase).searchAuditLogs(new AdminAuditLogSearchCommand(AdminAuditAction.MEMBER_SEARCH, 7L, 1, 50));
    }

    @Test
    @DisplayName("CUSTOMER 면 403 · SECURITY_005, 토큰이 없으면 401 · SECURITY_001 이고 유스케이스를 부르지 않는다")
    void nonAdminIsRejected() {
        SecurityEnvelopeAssertions.assertEnvelope(get(PATH, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        verifyNoInteractions(adminAuditLogAdminUseCase);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "?page=-1 | AUTH_133",
        "?size=0 | AUTH_134",
        "?size=101 | AUTH_134",
        "?actorMemberId=0 | AUTH_135",
        "?actorMemberId=-3 | AUTH_135",
        "?action=LOGOUT | AUTH_198",
        "?actorMemberId=abc | AUTH_198"
    })
    @DisplayName("파라미터 검증 코드 — 유스케이스를 부르지 않는다")
    void invalid(String query, String code) {
        MvcTestResult result = get(PATH + query, MemberRole.ADMIN);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        verifyNoInteractions(adminAuditLogAdminUseCase);
    }
}
