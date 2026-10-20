package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.StockAdjustmentHistoryAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminListResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.StockAdminResponse;
import com.commercefoundry.domainlayer.product.application.command.StockAdjustmentCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.StockAdminUseCase;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
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
 * 어드민 재고 조회 · 조정 이력 · 조정의 HTTP 계약 — 권한, 조정한 어드민(토큰의 memberId) 전달, 검증 오류 코드, 도메인 오류 봉투, 응답 모양.
 *
 * <p>{@code @AuthenticationPrincipal MemberPrincipal} 을 받으므로 {@code @WithMockUser} 대신 테스트 키로 서명한 실제 access 토큰을 보낸다 —
 * 슬라이스라 {@code *BeansConfig} 가 빠지므로 보안 구성을 직접 import 한다 ({@code security-guide.md} §5-3).
 */
@WebMvcTest(StockAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class StockAdminControllerTest {

    private static final String PATH = "/api/v1/admin/stocks/1001";
    private static final String STOCKS_PATH = "/api/v1/admin/stocks";
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

    @Nested
    @DisplayName("재고 조회 GET /api/v1/admin/stocks")
    class GetStocks {

        @Test
        @DisplayName("ADMIN 이면 200 — skuIds 를 요청 순서대로 넘기고, 아이디는 String, 재고 미등록 SKU 는 품절 · updatedAt null 이다")
        void adminIsAllowed() {
            given(stockAdminUseCase.getStocks(List.of(1002L, 1001L, 1002L))).willReturn(StockAdminListResponse.builder().stocks(List.of(
                StockAdminResponse.builder().skuId("1002").stockManaged(true).availableQuantity(0L).reservedQuantity(0L).updatedAt(null).build(),
                StockAdminResponse.builder().skuId("1001").stockManaged(true).availableQuantity(12L).reservedQuantity(3L)
                    .updatedAt(Instant.parse("2026-10-18T09:00:00Z")).build()
            )).build());

            MvcTestResult result = getStocks("1002,1001,1002", MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.stocks[0].skuId").isEqualTo("1002");
            assertThat(result).bodyJson().extractingPath("$.dataBody.stocks[0].stockManaged").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.stocks[0].availableQuantity").isEqualTo(0);
            assertThat(result).bodyJson().extractingPath("$.dataBody.stocks[0].reservedQuantity").isEqualTo(0);
            assertThat(result).bodyJson().extractingPath("$.dataBody.stocks[0].updatedAt").isNull();
            assertThat(result).bodyJson().extractingPath("$.dataBody.stocks[1].skuId").isEqualTo("1001");
            assertThat(result).bodyJson().extractingPath("$.dataBody.stocks[1].updatedAt").isEqualTo("2026-10-18T09:00:00Z");
        }

        @Test
        @DisplayName("CUSTOMER 면 403 · SECURITY_005 이다")
        void customerIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(getStocks("1001", MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(stockAdminUseCase);
        }

        @Test
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이다")
        void anonymousIsUnauthorized() {
            SecurityEnvelopeAssertions.assertEnvelope(
                mvc.get().uri(STOCKS_PATH).param("skuIds", "1001").exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED
            );
            verifyNoInteractions(stockAdminUseCase);
        }

        @Test
        @DisplayName("skuIds 파라미터가 없으면 400 · PRODUCT_199")
        void missing() {
            MvcTestResult result = mvc.get().uri(STOCKS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();

            assertFailure(result, HttpStatus.BAD_REQUEST, "PRODUCT_199", "skuIds 파라미터는 필수입니다.");
            verifyNoInteractions(stockAdminUseCase);
        }

        @ParameterizedTest(name = "skuIds={0} → {1}")
        @CsvSource(delimiter = '|', value = {
            "'' | PRODUCT_124 | skuIds 는 필수입니다.",
            "0 | PRODUCT_126 | skuIds 는 양수여야 합니다.",
            "1,-1 | PRODUCT_126 | skuIds 는 양수여야 합니다."
        })
        @DisplayName("skuIds 검증 코드")
        void invalid(String skuIds, String code, String message) {
            assertFailure(getStocks(skuIds, MemberRole.ADMIN), HttpStatus.BAD_REQUEST, code, message);
            verifyNoInteractions(stockAdminUseCase);
        }

        @Test
        @DisplayName("101개(중복 포함 원문 개수)면 400 · PRODUCT_125, 100개는 통과한다")
        void sizeLimit() {
            String hundred = LongStream.rangeClosed(1, 100).mapToObj(String::valueOf).collect(Collectors.joining(","));
            given(stockAdminUseCase.getStocks(any())).willReturn(StockAdminListResponse.builder().stocks(List.of()).build());

            assertFailure(getStocks(hundred + ",1", MemberRole.ADMIN), HttpStatus.BAD_REQUEST, "PRODUCT_125", "skuIds 는 최대 100개입니다.");
            assertThat(getStocks(hundred, MemberRole.ADMIN)).hasStatusOk();
        }

        @Test
        @DisplayName("숫자가 아니면 400 · PRODUCT_198")
        void notNumber() {
            assertFailure(getStocks("abc", MemberRole.ADMIN), HttpStatus.BAD_REQUEST, "PRODUCT_198", "skuIds 파라미터 형식이 올바르지 않습니다.");
            verifyNoInteractions(stockAdminUseCase);
        }
    }

    @Nested
    @DisplayName("조정 이력 GET /api/v1/admin/stocks/{skuId}/adjustments")
    class GetAdjustments {

        private static final String ADJUSTMENTS_PATH = "/api/v1/admin/stocks/1001/adjustments";

        @Test
        @DisplayName("ADMIN 이면 200 이고 PageResponse 모양이다 — 아이디 · 어드민 아이디는 String, 사유는 code · name · description, page · size 기본값 0 · 20")
        void adminIsAllowed() {
            StockAdjustmentHistoryAdminItem item = StockAdjustmentHistoryAdminItem.builder()
                .id("9001").beforeQuantity(10L).afterQuantity(12L).delta(2L).beforeStockManaged(true).afterStockManaged(true)
                .reason(StockAdjustmentReason.RESTOCK.toMetadata()).memo("10월 입고분").adminMemberId("4611686018427387904")
                .createdAt(Instant.parse("2026-10-18T09:00:00Z"))
                .build();
            given(stockAdminUseCase.getAdjustments(1001L, 0, 20)).willReturn(new PageResponse<>(List.of(item), 0, 20, 21L, 2));

            MvcTestResult result = getAdjustments(ADJUSTMENTS_PATH, MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.page").isEqualTo(0);
            assertThat(result).bodyJson().extractingPath("$.dataBody.size").isEqualTo(20);
            assertThat(result).bodyJson().extractingPath("$.dataBody.totalElements").isEqualTo(21);
            assertThat(result).bodyJson().extractingPath("$.dataBody.totalPages").isEqualTo(2);
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].id").isEqualTo("9001");
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].delta").isEqualTo(2);
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].reason.code").isEqualTo("RESTOCK");
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].reason.name").isEqualTo("입고");
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].adminMemberId").isEqualTo("4611686018427387904");
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].createdAt").isEqualTo("2026-10-18T09:00:00Z");
        }

        @Test
        @DisplayName("CUSTOMER 면 403 · SECURITY_005 이다")
        void customerIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(getAdjustments(ADJUSTMENTS_PATH, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(stockAdminUseCase);
        }

        @Test
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이다")
        void anonymousIsUnauthorized() {
            SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(ADJUSTMENTS_PATH).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
            verifyNoInteractions(stockAdminUseCase);
        }

        @Test
        @DisplayName("없는 SKU 면 404 · PRODUCT_002")
        void notFound() {
            given(stockAdminUseCase.getAdjustments(1001L, 0, 20)).willThrow(new ProductException(ProductErrorCode.NOT_FOUND_SKU));

            assertFailure(getAdjustments(ADJUSTMENTS_PATH, MemberRole.ADMIN), HttpStatus.NOT_FOUND, "PRODUCT_002", "존재하지 않는 SKU 입니다.");
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(delimiter = '|', value = {
            "/api/v1/admin/stocks/1001/adjustments?page=-1 | PRODUCT_101 | page 는 0 이상이어야 합니다.",
            "/api/v1/admin/stocks/1001/adjustments?size=0 | PRODUCT_102 | size 는 1 이상 100 이하여야 합니다.",
            "/api/v1/admin/stocks/1001/adjustments?size=101 | PRODUCT_102 | size 는 1 이상 100 이하여야 합니다.",
            "/api/v1/admin/stocks/0/adjustments | PRODUCT_123 | skuId 는 양수여야 합니다."
        })
        @DisplayName("page · size · skuId 검증 코드")
        void invalid(String uri, String code, String message) {
            assertFailure(getAdjustments(uri, MemberRole.ADMIN), HttpStatus.BAD_REQUEST, code, message);
            verifyNoInteractions(stockAdminUseCase);
        }

        @Test
        @DisplayName("page · size 를 그대로 넘긴다 (경계값 size=100)")
        void passesPaging() {
            given(stockAdminUseCase.getAdjustments(1001L, 3, 100)).willReturn(new PageResponse<>(List.of(), 3, 100, 0L, 0));

            assertThat(getAdjustments(ADJUSTMENTS_PATH + "?page=3&size=100", MemberRole.ADMIN)).hasStatusOk();
            verify(stockAdminUseCase).getAdjustments(1001L, 3, 100);
        }
    }

    private MvcTestResult getStocks(String skuIds, MemberRole role) {
        return mvc.get().uri(STOCKS_PATH).param("skuIds", skuIds).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    private MvcTestResult getAdjustments(String uri, MemberRole role) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
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
