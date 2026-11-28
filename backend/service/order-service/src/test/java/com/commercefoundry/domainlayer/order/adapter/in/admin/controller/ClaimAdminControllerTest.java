package com.commercefoundry.domainlayer.order.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.order.application.command.ClaimReturnApprovalCommand;
import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.domainlayer.order.application.port.in.ClaimAdminUseCase;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
 * 어드민 클레임 조회 API 의 인가 계약 (#587) — 클레임 조회는 주문 업무라 {@code AdminAuthority.ORDER_READ} 다. 테스트 값은 모두 가짜다.
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

    private MvcTestResult retry(MemberRole role, String body) {
        return mvc.post().uri(PATH + "/1/retries").header(HttpHeaders.AUTHORIZATION, bearer(role)).contentType(MediaType.APPLICATION_JSON).content(body)
            .exchange();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER", "MANAGER"})
    @DisplayName("다시 시도는 ORDER_MANAGE 라 ADMIN · OWNER · MANAGER 가 유스케이스에 닿고 버전은 본문, 관리자 아이디는 토큰에서 온다 (#17 A2b)")
    void orderManagersMayRetry(MemberRole role) {
        assertThat(retry(role, "{\"expectedVersion\":3}")).hasStatusOk();

        verify(claimAdminUseCase).retry(1L, 3L, TestTokens.MEMBER_ID);
    }

    @Test
    @DisplayName("STAFF · 고객은 다시 시도 403 · SECURITY_005, 토큰 없으면 401 이고 유스케이스를 부르지 않는다 (#17 A2b)")
    void readersMayNotRetry() {
        for (MemberRole role : List.of(MemberRole.STAFF, MemberRole.CUSTOMER)) {
            SecurityEnvelopeAssertions.assertEnvelope(retry(role, "{\"expectedVersion\":3}").getResponse(), SecurityErrorCode.FORBIDDEN);
        }
        SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(PATH + "/1/retries").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedVersion\":3}").exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        verifyNoInteractions(claimAdminUseCase);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{}", "{\"expectedVersion\":null}", "{\"expectedVersion\":-1}"})
    @DisplayName("expectedVersion 이 없거나 음수면 400 · ORDER_169 이고 유스케이스를 부르지 않는다 (#17 A2b)")
    void retryRequiresExpectedVersion(String body) {
        MvcTestResult result = retry(MemberRole.ADMIN, body);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_169");
        verifyNoInteractions(claimAdminUseCase);
    }

    @Test
    @DisplayName("양수가 아닌 클레임 아이디의 다시 시도는 400 · ORDER_166 이다 (#17 A2b)")
    void retryRejectsNonPositiveClaimId() {
        MvcTestResult result = mvc.post().uri(PATH + "/0/retries").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":3}").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_166");
        verifyNoInteractions(claimAdminUseCase);
    }

    // ---- 수동 환불 확정 · 복원 생략 종결 (#17 A3) ----

    private static final String DECISION_BODY = "{\"expectedVersion\":3,\"reason\":\"결제 관리 화면에서 취소 완료 확인\"}";

    private MvcTestResult decide(String action, MemberRole role, String body) {
        return mvc.post().uri(PATH + "/1/" + action).header(HttpHeaders.AUTHORIZATION, bearer(role)).contentType(MediaType.APPLICATION_JSON).content(body)
            .exchange();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER"})
    @DisplayName("수동 환불 확정 · 복원 생략 종결은 SETTLEMENT_MANAGE 라 ADMIN · OWNER 만 유스케이스에 닿고 버전 · 사유는 본문, 관리자 아이디는 토큰에서 온다 (#17 A3)")
    void settlementManagersMayDecide(MemberRole role) {
        assertThat(decide("refund-confirmation", role, DECISION_BODY)).hasStatusOk();
        assertThat(decide("completion", role, DECISION_BODY)).hasStatusOk();

        verify(claimAdminUseCase).confirmRefund(1L, 3L, "결제 관리 화면에서 취소 완료 확인", TestTokens.MEMBER_ID);
        verify(claimAdminUseCase).complete(1L, 3L, "결제 관리 화면에서 취소 완료 확인", TestTokens.MEMBER_ID);
    }

    @Test
    @DisplayName("MANAGER(다시 시도는 가능) · STAFF · 고객은 확정 · 종결 403 · SECURITY_005, 토큰 없으면 401 이고 유스케이스를 부르지 않는다 (#17 A3)")
    void othersMayNotDecide() {
        for (String action : List.of("refund-confirmation", "completion")) {
            for (MemberRole role : List.of(MemberRole.MANAGER, MemberRole.STAFF, MemberRole.CUSTOMER)) {
                SecurityEnvelopeAssertions.assertEnvelope(decide(action, role, DECISION_BODY).getResponse(), SecurityErrorCode.FORBIDDEN);
            }
            SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(PATH + "/1/" + action).contentType(MediaType.APPLICATION_JSON).content(DECISION_BODY)
                .exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        }
        verifyNoInteractions(claimAdminUseCase);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "{\"reason\":\"확인\"}|ORDER_169",
        "{\"expectedVersion\":-1,\"reason\":\"확인\"}|ORDER_169",
        "{\"expectedVersion\":3}|ORDER_170",
        "{\"expectedVersion\":3,\"reason\":\"   \"}|ORDER_170",
        "{\"expectedVersion\":3,\"reason\":\"확인\\n완료\"}|ORDER_151",
        // 도메인 isBlank(Character.isWhitespace)와 같은 기준 — @NotBlank 가 못 거르는 유니코드 공백만인 사유도 ORDER_170 하나다
        "{\"expectedVersion\":3,\"reason\":\"\\u3000\\u3000\"}|ORDER_170",
        "{\"expectedVersion\":3,\"reason\":\"\\u2028\"}|ORDER_170",
        "{\"expectedVersion\":3,\"reason\":\" \\u2029\\u3000\\t\"}|ORDER_170",
        // 이 DTO 만 서식 문자(Cf) · 줄 · 문단 구분자(Zl · Zp)를 섞어 쓰지 못한다
        "{\"expectedVersion\":3,\"reason\":\"확인\\u2028완료\"}|ORDER_151",
        "{\"expectedVersion\":3,\"reason\":\"확인\\u2029완료\"}|ORDER_151",
        "{\"expectedVersion\":3,\"reason\":\"확인\\u200B완료\"}|ORDER_151",
        "{\"expectedVersion\":3,\"reason\":\"\\u200B\"}|ORDER_151"
    })
    @DisplayName("확정 · 종결 본문 — expectedVersion 누락 · 음수 ORDER_169, 사유 없음 · 공백 ORDER_170, 제어 문자 ORDER_151 이고 유스케이스를 부르지 않는다 (#17 A3)")
    void decisionBodyValidation(String body, String code) {
        for (String action : List.of("refund-confirmation", "completion")) {
            MvcTestResult result = decide(action, MemberRole.OWNER, body);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        }
        verifyNoInteractions(claimAdminUseCase);
    }

    @Test
    @DisplayName("확정 · 종결 사유는 200자까지 받고 201자는 400 · ORDER_148 이다 (#17 A3)")
    void decisionReasonLength() {
        String max = "{\"expectedVersion\":3,\"reason\":\"" + "가".repeat(200) + "\"}";
        String over = "{\"expectedVersion\":3,\"reason\":\"" + "가".repeat(201) + "\"}";

        assertThat(decide("refund-confirmation", MemberRole.OWNER, max)).hasStatusOk();
        MvcTestResult result = decide("completion", MemberRole.OWNER, over);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_148");
        verify(claimAdminUseCase).confirmRefund(1L, 3L, "가".repeat(200), TestTokens.MEMBER_ID);
    }

    // ---- 반품 승인 · 거절 (#17 B2c-2) ----

    private static final String APPROVAL_BODY = "{\"expectedVersion\":0,\"fault\":\"SELLER\",\"restock\":false,"
        + "\"deductionAmount\":{\"amount\":3000,\"currency\":\"KRW\"},\"expectedRefundAmount\":{\"amount\":27000,\"currency\":\"KRW\"}}";
    private static final String REJECTION_BODY = "{\"expectedVersion\":0,\"reason\":\"검수 결과 사용 흔적이 있어 반품 불가\"}";

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER", "MANAGER"})
    @DisplayName("반품 승인 · 거절은 ORDER_MANAGE 라 ADMIN · OWNER · MANAGER 가 유스케이스에 닿고 본문이 명령으로, 관리자 아이디는 토큰에서 온다 (#17 B2c-2)")
    void orderManagersMayDecideReturns(MemberRole role) {
        assertThat(decide("return-approval", role, APPROVAL_BODY)).hasStatusOk();
        assertThat(decide("return-rejection", role, REJECTION_BODY)).hasStatusOk();

        verify(claimAdminUseCase).approveReturn(new ClaimReturnApprovalCommand(1L, 0L, ReturnRefundPolicy.Fault.SELLER, false,
            Money.of(3_000, CurrencyCode.KRW), Money.of(27_000, CurrencyCode.KRW)), TestTokens.MEMBER_ID);
        verify(claimAdminUseCase).rejectReturn(1L, 0L, "검수 결과 사용 흔적이 있어 반품 불가", TestTokens.MEMBER_ID);
    }

    @Test
    @DisplayName("반품 승인의 귀책은 선택이다 — 없으면 null 로 넘겨 서버가 기본 귀책을 쓴다 (#17 B2c-2)")
    void approvalFaultIsOptional() {
        String body = "{\"expectedVersion\":2,\"restock\":true,\"deductionAmount\":{\"amount\":0,\"currency\":\"KRW\"},"
            + "\"expectedRefundAmount\":{\"amount\":1,\"currency\":\"KRW\"}}";

        assertThat(decide("return-approval", MemberRole.MANAGER, body)).hasStatusOk();

        verify(claimAdminUseCase).approveReturn(new ClaimReturnApprovalCommand(1L, 2L, null, true, Money.of(0, CurrencyCode.KRW),
            Money.of(1, CurrencyCode.KRW)), TestTokens.MEMBER_ID);
    }

    @Test
    @DisplayName("STAFF · 고객은 반품 승인 · 거절 403 · SECURITY_005, 토큰 없으면 401 이고 유스케이스를 부르지 않는다 (#17 B2c-2)")
    void readersMayNotDecideReturns() {
        for (String action : List.of("return-approval", "return-rejection")) {
            String body = action.equals("return-approval") ? APPROVAL_BODY : REJECTION_BODY;
            for (MemberRole role : List.of(MemberRole.STAFF, MemberRole.CUSTOMER)) {
                SecurityEnvelopeAssertions.assertEnvelope(decide(action, role, body).getResponse(), SecurityErrorCode.FORBIDDEN);
            }
            SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(PATH + "/1/" + action).contentType(MediaType.APPLICATION_JSON).content(body)
                .exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        }
        verifyNoInteractions(claimAdminUseCase);
    }

    @ParameterizedTest(name = "{1}: {0}")
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "{\"restock\":true,\"deductionAmount\":{\"amount\":0,\"currency\":\"KRW\"},\"expectedRefundAmount\":{\"amount\":1,\"currency\":\"KRW\"}}|ORDER_169",
        "{\"expectedVersion\":-1,\"restock\":true,\"deductionAmount\":{\"amount\":0,\"currency\":\"KRW\"},"
            + "\"expectedRefundAmount\":{\"amount\":1,\"currency\":\"KRW\"}}|ORDER_169",
        "{\"expectedVersion\":0,\"deductionAmount\":{\"amount\":0,\"currency\":\"KRW\"},\"expectedRefundAmount\":{\"amount\":1,\"currency\":\"KRW\"}}|ORDER_171",
        "{\"expectedVersion\":0,\"restock\":true,\"expectedRefundAmount\":{\"amount\":1,\"currency\":\"KRW\"}}|ORDER_172",
        "{\"expectedVersion\":0,\"restock\":true,\"deductionAmount\":{\"currency\":\"KRW\"},\"expectedRefundAmount\":{\"amount\":1,\"currency\":\"KRW\"}}|ORDER_172",
        "{\"expectedVersion\":0,\"restock\":true,\"deductionAmount\":{\"amount\":-1,\"currency\":\"KRW\"},"
            + "\"expectedRefundAmount\":{\"amount\":1,\"currency\":\"KRW\"}}|ORDER_172",
        "{\"expectedVersion\":0,\"restock\":true,\"deductionAmount\":{\"amount\":0},\"expectedRefundAmount\":{\"amount\":1,\"currency\":\"KRW\"}}|ORDER_172",
        "{\"expectedVersion\":0,\"restock\":true,\"deductionAmount\":{\"amount\":0,\"currency\":\"KRW\"}}|ORDER_173",
        "{\"expectedVersion\":0,\"restock\":true,\"deductionAmount\":{\"amount\":0,\"currency\":\"KRW\"},"
            + "\"expectedRefundAmount\":{\"amount\":0,\"currency\":\"KRW\"}}|ORDER_173",
        "{\"expectedVersion\":0,\"restock\":true,\"deductionAmount\":{\"amount\":0,\"currency\":\"KRW\"},\"expectedRefundAmount\":{\"amount\":1}}|ORDER_173",
        // 모르는 귀책 · 통화는 역직렬화 실패라 폴백 ORDER_100 이다
        "{\"expectedVersion\":0,\"fault\":\"BOTH\",\"restock\":true,\"deductionAmount\":{\"amount\":0,\"currency\":\"KRW\"},"
            + "\"expectedRefundAmount\":{\"amount\":1,\"currency\":\"KRW\"}}|ORDER_100",
        "{\"expectedVersion\":0,\"restock\":true,\"deductionAmount\":{\"amount\":0,\"currency\":\"XYZ\"},"
            + "\"expectedRefundAmount\":{\"amount\":1,\"currency\":\"KRW\"}}|ORDER_100"
    })
    @DisplayName("반품 승인 본문 — expectedVersion ORDER_169, restock ORDER_171, deductionAmount ORDER_172, expectedRefundAmount ORDER_173 이고 유스케이스를 부르지 않는다")
    void approvalBodyValidation(String body, String code) {
        MvcTestResult result = decide("return-approval", MemberRole.ADMIN, body);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(claimAdminUseCase);
    }

    @ParameterizedTest(name = "{1}: {0}")
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "{\"reason\":\"확인\"}|ORDER_169",
        "{\"expectedVersion\":0}|ORDER_170",
        "{\"expectedVersion\":0,\"reason\":\"\\u3000\"}|ORDER_170",
        "{\"expectedVersion\":0,\"reason\":\"확인\\n완료\"}|ORDER_151"
    })
    @DisplayName("반품 거절 본문은 확정 · 종결과 같은 DTO 검증이다 — ORDER_169 · 170 · 151 (#17 B2c-2)")
    void rejectionBodyValidation(String body, String code) {
        MvcTestResult result = decide("return-rejection", MemberRole.ADMIN, body);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(claimAdminUseCase);
    }

    // ---- 결제 후 취소 거절 (#17 A4) ----

    private static final String CANCELLATION_REJECTION = "cancellation-rejection";
    private static final String CANCELLATION_REJECTION_BODY = "{\"expectedVersion\":5,\"reason\":\"결제사 화면에서 환불 없음 확인\"}";

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER"})
    @DisplayName("취소 거절은 SETTLEMENT_MANAGE 라 ADMIN · OWNER 만 유스케이스에 닿고 버전 · 사유는 본문, 관리자 아이디는 토큰에서 온다 (#17 A4)")
    void settlementManagersMayRejectCancellation(MemberRole role) {
        assertThat(decide(CANCELLATION_REJECTION, role, CANCELLATION_REJECTION_BODY)).hasStatusOk();

        verify(claimAdminUseCase).rejectCancellation(1L, 5L, "결제사 화면에서 환불 없음 확인", TestTokens.MEMBER_ID);
    }

    @Test
    @DisplayName("MANAGER(반품 거절은 가능) · STAFF · 고객은 취소 거절 403 · SECURITY_005, 토큰 없으면 401 이고 유스케이스를 부르지 않는다 (#17 A4)")
    void othersMayNotRejectCancellation() {
        for (MemberRole role : List.of(MemberRole.MANAGER, MemberRole.STAFF, MemberRole.CUSTOMER)) {
            SecurityEnvelopeAssertions.assertEnvelope(decide(CANCELLATION_REJECTION, role, CANCELLATION_REJECTION_BODY).getResponse(),
                SecurityErrorCode.FORBIDDEN);
        }
        SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(PATH + "/1/" + CANCELLATION_REJECTION).contentType(MediaType.APPLICATION_JSON)
            .content(CANCELLATION_REJECTION_BODY).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        verifyNoInteractions(claimAdminUseCase);
    }

    @ParameterizedTest(name = "{1}: {0}")
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "{\"reason\":\"확인\"}|ORDER_169",
        "{\"expectedVersion\":-1,\"reason\":\"확인\"}|ORDER_169",
        "{\"expectedVersion\":5}|ORDER_170",
        "{\"expectedVersion\":5,\"reason\":\"\\u3000\"}|ORDER_170",
        "{\"expectedVersion\":5,\"reason\":\"확인\\n완료\"}|ORDER_151",
        "{\"expectedVersion\":5,\"reason\":\"확인\\u200B완료\"}|ORDER_151"
    })
    @DisplayName("취소 거절 본문은 확정 · 종결과 같은 DTO 검증이다 — ORDER_169 · 170 · 151 이고 유스케이스를 부르지 않는다 (#17 A4)")
    void cancellationRejectionBodyValidation(String body, String code) {
        MvcTestResult result = decide(CANCELLATION_REJECTION, MemberRole.OWNER, body);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(claimAdminUseCase);
    }

    @Test
    @DisplayName("취소 거절 사유는 200자까지 받고 201자는 400 · ORDER_148, 양수가 아닌 아이디는 400 · ORDER_166 이다 (#17 A4)")
    void cancellationRejectionReasonLengthAndClaimId() {
        String over = "{\"expectedVersion\":5,\"reason\":\"" + "가".repeat(201) + "\"}";

        MvcTestResult tooLong = decide(CANCELLATION_REJECTION, MemberRole.OWNER, over);
        MvcTestResult nonPositive = mvc.post().uri(PATH + "/0/" + CANCELLATION_REJECTION).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER))
            .contentType(MediaType.APPLICATION_JSON).content(CANCELLATION_REJECTION_BODY).exchange();

        assertThat(tooLong).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_148");
        assertThat(nonPositive).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_166");
        verifyNoInteractions(claimAdminUseCase);
    }

    @Test
    @DisplayName("양수가 아닌 클레임 아이디의 반품 승인 · 거절은 400 · ORDER_166 이다 (#17 B2c-2)")
    void returnDecisionRejectsNonPositiveClaimId() {
        MvcTestResult approval = mvc.post().uri(PATH + "/0/return-approval").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content(APPROVAL_BODY).exchange();
        MvcTestResult rejection = mvc.post().uri(PATH + "/0/return-rejection").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content(REJECTION_BODY).exchange();

        assertThat(approval).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_166");
        assertThat(rejection).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_166");
        verifyNoInteractions(claimAdminUseCase);
    }
}
