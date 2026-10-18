package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminResponse;
import com.commercefoundry.domainlayer.product.application.command.StockAdjustmentCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.StockAdminUseCase;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 어드민 재고 조정의 HTTP 계약 — 권한, 조정한 어드민(토큰의 memberId) 전달, 검증 오류 코드, 도메인 오류 봉투.
 *
 * <p>{@code @AuthenticationPrincipal MemberPrincipal} 을 받으므로 {@code @WithMockUser} 대신 테스트 키로 서명한 실제 access 토큰을 보낸다 —
 * 슬라이스라 {@code *BeansConfig} 가 빠지므로 보안 구성을 직접 import 한다 ({@code security-guide.md} §5-3).
 */
@WebMvcTest(StockAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class StockAdminControllerTest {

    private static final String PATH = "/api/v1/admin/stocks/1001";
    private static final String VALID_BODY = """
        {"quantity": 12, "expectedQuantity": 10, "reason": "RESTOCK", "memo": "10월 입고분"}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private StockAdminUseCase stockAdminUseCase;

    @Nested
    @DisplayName("권한")
    class Authorization {

        @Test
        @DisplayName("ADMIN 이면 200 이고 Response 봉투다 — 조정한 어드민은 토큰의 memberId 로 넘긴다")
        void adminIsAllowed() {
            given(stockAdminUseCase.adjustStock(any())).willReturn(StockAdminResponse.builder()
                .skuId("1001")
                .stockManaged(true)
                .availableQuantity(12L)
                .reservedQuantity(3L)
                .updatedAt(Instant.parse("2026-10-18T09:00:00Z"))
                .build());

            MvcTestResult result = patch(VALID_BODY, MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.skuId").isEqualTo("1001");
            assertThat(result).bodyJson().extractingPath("$.dataBody.stockManaged").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.availableQuantity").isEqualTo(12);
            assertThat(result).bodyJson().extractingPath("$.dataBody.reservedQuantity").isEqualTo(3);
            assertThat(result).bodyJson().extractingPath("$.dataBody.updatedAt").isEqualTo("2026-10-18T09:00:00Z");
            verify(stockAdminUseCase).adjustStock(StockAdjustmentCommand.builder()
                .skuId(1001L)
                .quantity(12L)
                .expectedQuantity(10L)
                .reason(StockAdjustmentReason.RESTOCK)
                .memo("10월 입고분")
                .adminMemberId(TestTokens.MEMBER_ID)
                .build());
        }

        @Test
        @DisplayName("CUSTOMER 면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void customerIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(patch(VALID_BODY, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(stockAdminUseCase);
        }

        @Test
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이다")
        void anonymousIsUnauthorized() {
            MvcTestResult result = mvc.patch().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY).exchange();

            SecurityEnvelopeAssertions.assertEnvelope(result.getResponse(), SecurityErrorCode.UNAUTHORIZED);
            verifyNoInteractions(stockAdminUseCase);
        }
    }

    @Nested
    @DisplayName("도메인 오류")
    class DomainErrors {

        @Test
        @DisplayName("없는 SKU 면 404 · PRODUCT_002")
        void notFound() {
            given(stockAdminUseCase.adjustStock(any())).willThrow(new ProductException(ProductErrorCode.NOT_FOUND_SKU));

            assertFailure(patch(VALID_BODY, MemberRole.ADMIN), HttpStatus.NOT_FOUND, "PRODUCT_002", "존재하지 않는 SKU 입니다.");
        }

        @Test
        @DisplayName("기대 수량이 다르면 409 · PRODUCT_020")
        void conflict() {
            given(stockAdminUseCase.adjustStock(any())).willThrow(new ProductException(ProductErrorCode.STOCK_ADJUSTMENT_CONFLICT));

            assertFailure(patch(VALID_BODY, MemberRole.ADMIN), HttpStatus.CONFLICT, "PRODUCT_020", "재고가 그 사이 바뀌었습니다. 현재 재고를 다시 확인한 뒤 조정해 주세요.");
        }
    }

    @Nested
    @DisplayName("요청 검증")
    class Validation {

        @ParameterizedTest(name = "{0} → {1} ({2})")
        @CsvSource(delimiter = '|', value = {
            "{\"quantity\": -1, \"expectedQuantity\": 0, \"reason\": \"RESTOCK\"} | PRODUCT_106 | quantity | quantity 는 0 이상이어야 합니다.",
            "{\"quantity\": 1, \"expectedQuantity\": -1, \"reason\": \"RESTOCK\"} | PRODUCT_107 | expectedQuantity | expectedQuantity 는 0 이상이어야 합니다.",
            "{\"quantity\": 1, \"expectedQuantity\": 0} | PRODUCT_108 | reason | reason 은 필수입니다.",
            "{\"stockManaged\": true, \"reason\": \"OTHER\", \"memo\": \"MEMO_201\"} | PRODUCT_109 | memo | memo 는 200자 이하여야 합니다.",
            "{\"expectedQuantity\": 0, \"reason\": \"RESTOCK\"} | PRODUCT_110 | targetPresent | quantity · stockManaged 중 하나 이상을 보내야 합니다.",
            "{\"quantity\": 1, \"reason\": \"RESTOCK\"} | PRODUCT_111 | expectedQuantityPresent | quantity 를 바꾸려면 expectedQuantity 가 필수입니다."
        })
        @DisplayName("필드별 검증 코드")
        void fieldErrors(String body, String code, String field, String message) {
            MvcTestResult result = patch(body.replace("MEMO_201", "가".repeat(201)), MemberRole.ADMIN);

            assertFailure(result, HttpStatus.BAD_REQUEST, code, message);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
            verifyNoInteractions(stockAdminUseCase);
        }

        @Test
        @DisplayName("메모 200자 · 수량 0 · 관리 여부만 보내는 요청은 통과한다 (경계값)")
        void boundariesPass() {
            given(stockAdminUseCase.adjustStock(any())).willReturn(StockAdminResponse.builder().skuId("1001").build());

            assertThat(patch("{\"quantity\": 0, \"expectedQuantity\": 0, \"reason\": \"RECOUNT\", \"memo\": \"" + "가".repeat(200) + "\"}", MemberRole.ADMIN))
                .hasStatusOk();
            assertThat(patch("{\"stockManaged\": false, \"reason\": \"MANAGEMENT_CHANGE\"}", MemberRole.ADMIN)).hasStatusOk();
        }

        @Test
        @DisplayName("여러 오류가 겹치면 필드 선언 순서로 첫 오류가 대표 코드다")
        void ordering() {
            MvcTestResult result = patch("{\"quantity\": -1}", MemberRole.ADMIN);

            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_106");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[1].code").isEqualTo("PRODUCT_108");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[2].code").isEqualTo("PRODUCT_111");
        }

        @Test
        @DisplayName("깨진 JSON 은 400 · PRODUCT_100 이고 Response 봉투다")
        void brokenJson() {
            MvcTestResult result = patch("{\"quantity\": 1,", MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_100");
            verifyNoInteractions(stockAdminUseCase);
        }

        @Test
        @DisplayName("사유가 enum 에 없는 값이면 400 · PRODUCT_100 이고 필드와 허용 값을 알린다")
        void unknownReason() {
            MvcTestResult result = patch("{\"quantity\": 1, \"expectedQuantity\": 0, \"reason\": \"LOST\"}", MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_100");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("reason");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage")
                .isEqualTo("reason 값이 올바르지 않습니다. 허용 값: RESTOCK, RECOUNT, DAMAGE, MANAGEMENT_CHANGE, OTHER");
            verifyNoInteractions(stockAdminUseCase);
        }

        @Test
        @DisplayName("SKU 아이디가 숫자가 아니면 400 · PRODUCT_198")
        void skuIdNotNumber() {
            MvcTestResult result = mvc.patch().uri("/api/v1/admin/stocks/abc")
                .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                .exchange();

            assertFailure(result, HttpStatus.BAD_REQUEST, "PRODUCT_198", "skuId 파라미터 형식이 올바르지 않습니다.");
            verifyNoInteractions(stockAdminUseCase);
        }

        @ParameterizedTest(name = "skuId={0}")
        @CsvSource({"0", "-1"})
        @DisplayName("SKU 아이디가 양수가 아니면 400 · PRODUCT_123 — internal 경로 변수와 같은 동작이다")
        void skuIdNotPositive(String skuId) {
            MvcTestResult result = mvc.patch().uri("/api/v1/admin/stocks/" + skuId)
                .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                .exchange();

            assertFailure(result, HttpStatus.BAD_REQUEST, "PRODUCT_123", "skuId 는 양수여야 합니다.");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("skuId");
            verifyNoInteractions(stockAdminUseCase);
        }

        @Test
        @DisplayName("경로 변수와 본문이 함께 틀리면 둘 다 알린다 — 본문 필드 이름 · 코드는 그대로다")
        void skuIdAndBodyInvalid() {
            MvcTestResult result = mvc.patch().uri("/api/v1/admin/stocks/0")
                .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\": -1, \"expectedQuantity\": 0, \"reason\": \"RESTOCK\"}")
                .exchange();

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[*].code").asArray()
                .containsExactlyInAnyOrder("PRODUCT_123", "PRODUCT_106");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[*].field").asArray()
                .containsExactlyInAnyOrder("skuId", "quantity");
            verifyNoInteractions(stockAdminUseCase);
        }
    }

    private MvcTestResult patch(String body, MemberRole role) {
        return mvc.patch().uri(PATH)
            .header(HttpHeaders.AUTHORIZATION, bearer(role))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .exchange();
    }

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    private static void assertFailure(MvcTestResult result, HttpStatus status, String code, String message) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo(message);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }
}
