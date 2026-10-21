package com.commercefoundry.domainlayer.member.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminMemberItem;
import com.commercefoundry.domainlayer.member.application.port.in.MemberAdminUseCase;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 어드민 회원 조회의 HTTP 계약 — 권한(URL 규칙 + {@code hasRole('ADMIN')}), 조회한 관리자(토큰의 memberId) 전달, 페이지 파라미터 검증 코드, 응답 모양.
 * 슬라이스라 보안 구성을 직접 import 하고, 테스트 키로 서명한 실제 access 토큰을 보낸다.
 */
@WebMvcTest(MemberAdminController.class)
@Import({
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class MemberAdminControllerTest {

    private static final String PATH = "/api/v1/admin/members";
    private static final PageResponse<AdminMemberItem> PAGE = new PageResponse<>(List.of(AdminMemberItem.builder()
        .memberId("4611686018427387905").email("cu***@e***.com").name("홍*동").phone("010-****-5678").role(MemberRole.CUSTOMER)
        .createdAt(Instant.parse("2026-10-18T03:00:00Z")).build()), 0, 20, 1, 1);

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private MemberAdminUseCase memberAdminUseCase;

    @Nested
    @DisplayName("권한")
    class Authorization {

        @Test
        @DisplayName("ADMIN 이면 200 PageResponse 이고 기본 page 0 · size 20 · 역할 전체, 조회한 관리자는 토큰의 memberId 다")
        void adminIsAllowed() {
            given(memberAdminUseCase.searchMembers(TestTokens.MEMBER_ID, null, 0, 20)).willReturn(PAGE);

            MvcTestResult result = get(PATH, MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.page").isEqualTo(0);
            assertThat(result).bodyJson().extractingPath("$.dataBody.size").isEqualTo(20);
            assertThat(result).bodyJson().extractingPath("$.dataBody.totalElements").isEqualTo(1);
            assertThat(result).bodyJson().extractingPath("$.dataBody.totalPages").isEqualTo(1);
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].memberId").isEqualTo("4611686018427387905");
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].email").isEqualTo("cu***@e***.com");
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].name").isEqualTo("홍*동");
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].phone").isEqualTo("010-****-5678");
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].role").isEqualTo("CUSTOMER");
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].createdAt").isEqualTo("2026-10-18T03:00:00Z");
            verify(memberAdminUseCase).searchMembers(TestTokens.MEMBER_ID, null, 0, 20);
        }

        @Test
        @DisplayName("page · size · role 은 그대로 넘긴다")
        void passesParameters() {
            given(memberAdminUseCase.searchMembers(TestTokens.MEMBER_ID, MemberRole.ADMIN, 3, 100)).willReturn(PAGE);

            assertThat(get(PATH + "?page=3&size=100&role=ADMIN", MemberRole.ADMIN)).hasStatusOk();
            verify(memberAdminUseCase).searchMembers(TestTokens.MEMBER_ID, MemberRole.ADMIN, 3, 100);
        }

        @Test
        @DisplayName("CUSTOMER 면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void customerIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(get(PATH, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(memberAdminUseCase);
        }

        @Test
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이다")
        void anonymousIsUnauthorized() {
            SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
            verifyNoInteractions(memberAdminUseCase);
        }
    }

    @Nested
    @DisplayName("요청 검증")
    class Validation {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "page 음수   | page=-1    | AUTH_133 | page",
            "size 0      | size=0     | AUTH_134 | size",
            "size 101    | size=101   | AUTH_134 | size",
            "page 문자   | page=x     | AUTH_198 | page",
            "role 오타   | role=ROOT  | AUTH_198 | role"
        })
        @DisplayName("파라미터가 틀리면 400 · 필드별 코드이고 유스케이스를 부르지 않는다")
        void invalidParameters(String caseName, String query, String code, String field) {
            MvcTestResult result = get(PATH + "?" + query, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
            verifyNoInteractions(memberAdminUseCase);
        }

        @Test
        @DisplayName("마지막 페이지를 넘긴 page 는 400 이 아니다 — 유스케이스가 빈 목록을 준다")
        void pageBeyondLastIsNotAnError() {
            given(memberAdminUseCase.searchMembers(anyLong(), isNull(), eq(999), anyInt())).willReturn(new PageResponse<>(List.of(), 999, 20, 1, 1));

            assertThat(get(PATH + "?page=999", MemberRole.ADMIN)).hasStatusOk();
        }
    }

    private MvcTestResult get(String uri, MemberRole role) {
        String token = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }
}
