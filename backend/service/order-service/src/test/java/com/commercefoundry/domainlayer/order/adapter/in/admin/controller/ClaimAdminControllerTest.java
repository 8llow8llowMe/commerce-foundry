package com.commercefoundry.domainlayer.order.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.ClaimAdminResponse;
import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.domainlayer.order.application.port.in.ClaimAdminUseCase;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 어드민 클레임 조회 · 수습 API 의 HTTP 계약 (#587 · #17 PR-A) — 조회는 ORDER_READ, 수습은 ORDER_MANAGE. 수습 본문 검증 · 응답 칸도 본다.
 * 클레임 조회 API 의 인가 계약 (#587) — 클레임 조회는 주문 업무라 {@code AdminAuthority.ORDER_READ} 다. 테스트 값은 모두 가짜다.
 * 슬라이스라 {@code *BeansConfig} 가 빠지므로 보안 구성을 직접 import 한다 ({@code security-guide.md} §5-3).
 */
@WebMvcTest(ClaimAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class ClaimAdminControllerTest {

    private static final String PATH = "/api/v1/admin/claims";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ClaimAdminUseCase claimAdminUseCase;

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    private MvcTestResult getClaims(MemberRole role) {
        return mvc.get().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER", "MANAGER", "STAFF"})
    @DisplayName("클레임 목록은 ORDER_READ 라 ADMIN · OWNER · MANAGER · STAFF 모두 200 이고 관리자 아이디는 토큰에서 온다 (#587)")
    void orderReadersMayListClaims(MemberRole role) {
        given(claimAdminUseCase.getClaims(any(), anyLong())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        assertThat(getClaims(role)).hasStatusOk();

        verify(claimAdminUseCase).getClaims(any(ClaimAdminFilter.class), eq(TestTokens.MEMBER_ID));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER", "MANAGER", "STAFF"})
    @DisplayName("클레임 상세도 ORDER_READ 라 ADMIN · OWNER · MANAGER · STAFF 모두 유스케이스에 닿고 관리자 아이디는 토큰에서 온다 (#587)")
    void orderReadersMayReadClaim(MemberRole role) {
        assertThat(mvc.get().uri(PATH + "/1").header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange()).hasStatusOk();

        verify(claimAdminUseCase).getClaim(1L, TestTokens.MEMBER_ID);
    }

    @Test
    @DisplayName("고객 토큰은 목록 · 상세 모두 403 · SECURITY_005, 토큰 없으면 401 · SECURITY_001 이고 유스케이스를 부르지 않는다")
    void customerAndAnonymousMayNotListClaims() {
        for (String uri : List.of(PATH, PATH + "/1")) {
            SecurityEnvelopeAssertions.assertEnvelope(
                mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
            SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(uri).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        }
        verifyNoInteractions(claimAdminUseCase);
    }

    // --- 결제 후 취소 수습 (#17 PR-A) ---

    private static final String REASON_BODY = "{\"reason\":\"PG 거절 확인\"}";

    private MvcTestResult post(String uri, String body, MemberRole role) {
        var builder = mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(role));
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return builder.exchange();
    }

    private static ClaimAdminResponse decided() {
        return new ClaimAdminResponse("1", "2", Claim.Type.CANCELLATION.toMetadata(), Claim.Status.REJECTED.toMetadata(), null, "77", null, 0, null,
            "PG 거절 확인", String.valueOf(TestTokens.MEMBER_ID), Instant.parse("2026-11-06T00:00:00Z"), Instant.parse("2026-11-05T00:00:00Z"), List.of());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER", "MANAGER"})
    @DisplayName("수습 4개는 ORDER_MANAGE 라 ADMIN · OWNER · MANAGER 는 200 이고, 사유와 관리자 아이디(토큰)를 유스케이스에 넘긴다")
    void orderManagersMayProcessClaims(MemberRole role) {
        given(claimAdminUseCase.reject(anyLong(), any(), anyLong())).willReturn(decided());

        assertThat(post(PATH + "/1/retries", null, role)).hasStatusOk();
        MvcTestResult rejected = post(PATH + "/1/rejection", REASON_BODY, role);
        assertThat(post(PATH + "/1/refund-confirmation", REASON_BODY, role)).hasStatusOk();
        assertThat(post(PATH + "/1/completion", REASON_BODY, role)).hasStatusOk();

        assertThat(rejected).hasStatusOk();
        assertThat(rejected).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("REJECTED");
        assertThat(rejected).bodyJson().extractingPath("$.dataBody.paymentId").isEqualTo("77");
        assertThat(rejected).bodyJson().extractingPath("$.dataBody.decisionReason").isEqualTo("PG 거절 확인");
        assertThat(rejected).bodyJson().extractingPath("$.dataBody.decidedById").isEqualTo(String.valueOf(TestTokens.MEMBER_ID));
        assertThat(rejected).bodyJson().extractingPath("$.dataBody.attemptCount").isEqualTo(0);
        verify(claimAdminUseCase).retry(1L, TestTokens.MEMBER_ID);
        verify(claimAdminUseCase).reject(1L, "PG 거절 확인", TestTokens.MEMBER_ID);
        verify(claimAdminUseCase).confirmRefund(1L, "PG 거절 확인", TestTokens.MEMBER_ID);
        verify(claimAdminUseCase).complete(1L, "PG 거절 확인", TestTokens.MEMBER_ID);
    }

    @Test
    @DisplayName("STAFF 는 ORDER_MANAGE 가 없어 수습 4개가 403 SECURITY_005, 고객 403 · 토큰 없음 401 — 유스케이스를 부르지 않는다")
    void staffCustomerAndAnonymousMayNotProcessClaims() {
        for (String uri : List.of(PATH + "/1/retries", PATH + "/1/rejection", PATH + "/1/refund-confirmation", PATH + "/1/completion")) {
            SecurityEnvelopeAssertions.assertEnvelope(post(uri, REASON_BODY, MemberRole.STAFF).getResponse(), SecurityErrorCode.FORBIDDEN);
            SecurityEnvelopeAssertions.assertEnvelope(post(uri, REASON_BODY, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(REASON_BODY).exchange().getResponse(),
                SecurityErrorCode.UNAUTHORIZED);
        }
        verifyNoInteractions(claimAdminUseCase);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "{}                                  | ORDER_169",
        "{\"reason\":\"   \"}                | ORDER_169",
        "{\"reason\":\"CONTROL\"}            | ORDER_151",
        "{\"reason\":\"LONG\"}               | ORDER_148"
    })
    @DisplayName("사유가 없거나 공백 · 제어 문자 · 200자 초과면 400 이고 유스케이스를 부르지 않는다")
    void reasonIsValidated(String body, String code) {
        String payload = body.strip().replace("LONG", "가".repeat(201)).replace("CONTROL", "줄\\n바꿈");
        for (String action : List.of("rejection", "refund-confirmation", "completion")) {
            MvcTestResult result = post(PATH + "/1/" + action, payload, MemberRole.ADMIN);
            assertThat(result).hasStatus(400);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        }
        verifyNoInteractions(claimAdminUseCase);
    }

    @Test
    @DisplayName("양수가 아닌 클레임 아이디는 400 ORDER_166 이다")
    void claimIdMustBePositive() {
        MvcTestResult result = post(PATH + "/0/retries", null, MemberRole.ADMIN);

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_166");
        verifyNoInteractions(claimAdminUseCase);
    }
}
