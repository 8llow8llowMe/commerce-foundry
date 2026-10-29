package com.commercefoundry.domainlayer.skucost.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.item.SkuCostAdminItem;
import com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.response.SkuCurrentCostsAdminResponse;
import com.commercefoundry.domainlayer.skucost.application.command.SkuCostCreateCommand;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostErrorCode;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostException;
import com.commercefoundry.domainlayer.skucost.application.model.SkuCostTypeCode;
import com.commercefoundry.domainlayer.skucost.application.port.in.SkuCostAdminUseCase;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 어드민 SKU 원가의 HTTP 계약 — ADMIN 만, 기록한 어드민은 토큰의 memberId, 필드 검증 코드(SKU_COST_101~110), 도메인 오류 봉투(SKU_COST_001~006 · 없는 SKU PRODUCT_002), 201 응답 모양.
 */
@WebMvcTest(SkuCostAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class SkuCostAdminControllerTest {

    private static final String COSTS = "/api/v1/admin/skus/1001/costs";
    private static final String HISTORIES = COSTS + "/histories";
    private static final String VALID_BODY = """
        {"costType": "GOODS", "amount": {"amount": 12000, "currency": "KRW"}, "effectiveFrom": "2026-10-01T00:00:00Z", "reason": "  10월 원단 단가 인상  "}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private SkuCostAdminUseCase skuCostAdminUseCase;

    @Nested
    @DisplayName("권한")
    class Authorization {

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
            "GET, /api/v1/admin/skus/1001/costs",
            "GET, /api/v1/admin/skus/1001/costs/histories",
            "POST, /api/v1/admin/skus/1001/costs"
        })
        @DisplayName("CUSTOMER 면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void customerIsForbidden(String method, String uri) {
            SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.valueOf(method), uri, VALID_BODY, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(skuCostAdminUseCase);
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
            "GET, /api/v1/admin/skus/1001/costs",
            "GET, /api/v1/admin/skus/1001/costs/histories",
            "POST, /api/v1/admin/skus/1001/costs"
        })
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이고 유스케이스를 부르지 않는다")
        void anonymousIsUnauthorized(String method, String uri) {
            SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.valueOf(method), uri, VALID_BODY, null).getResponse(), SecurityErrorCode.UNAUTHORIZED);
            verifyNoInteractions(skuCostAdminUseCase);
        }
    }

    @Nested
    @DisplayName("추가")
    class Create {

        @Test
        @DisplayName("ADMIN 이면 201 이고 항목 하나다 — 기록한 어드민은 토큰의 memberId, 사유는 앞뒤 공백을 뺀다")
        void created() {
            given(skuCostAdminUseCase.createCost(any())).willReturn(item());

            MvcTestResult result = exchange(HttpMethod.POST, COSTS, VALID_BODY, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.costId").isEqualTo("501");
            assertThat(result).bodyJson().extractingPath("$.dataBody.costType.code").isEqualTo("GOODS");
            assertThat(result).bodyJson().extractingPath("$.dataBody.amount.amount").isEqualTo(12000);
            assertThat(result).bodyJson().extractingPath("$.dataBody.amount.currency").isEqualTo("KRW");
            assertThat(result).bodyJson().extractingPath("$.dataBody.effectiveFrom").isEqualTo("2026-10-01T00:00:00Z");
            assertThat(result).bodyJson().extractingPath("$.dataBody.adminMemberId").isEqualTo(Long.toString(TestTokens.MEMBER_ID));
            verify(skuCostAdminUseCase).createCost(SkuCostCreateCommand.builder()
                .skuId(1001L)
                .costType(SkuCostType.GOODS)
                .amount(Money.of(12_000, CurrencyCode.KRW))
                .effectiveFrom(Instant.parse("2026-10-01T00:00:00Z"))
                .reason("10월 원단 단가 인상")
                .adminMemberId(TestTokens.MEMBER_ID)
                .build());
        }

        @Test
        @DisplayName("effectiveFrom 을 빼면 null 로 넘긴다 — 서버 시각은 Processor 가 정한다. 0원도 받는다")
        void effectiveFromIsOptional() {
            given(skuCostAdminUseCase.createCost(any())).willReturn(item());

            MvcTestResult result = exchange(HttpMethod.POST, COSTS, """
                {"costType": "PACKAGING", "amount": {"amount": 0, "currency": "USD"}, "reason": "포장재 무상 제공"}
                """, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.CREATED);
            verify(skuCostAdminUseCase).createCost(SkuCostCreateCommand.builder()
                .skuId(1001L)
                .costType(SkuCostType.PACKAGING)
                .amount(Money.of(0, CurrencyCode.USD))
                .effectiveFrom(null)
                .reason("포장재 무상 제공")
                .adminMemberId(TestTokens.MEMBER_ID)
                .build());
        }

        @ParameterizedTest(name = "{1}")
        @CsvSource(delimiter = '|', textBlock = """
            {"amount": {"amount": 1, "currency": "KRW"}, "reason": "r"}                                                      | SKU_COST_101
            {"costType": "GOODS", "reason": "r"}                                                                             | SKU_COST_102
            {"costType": "GOODS", "amount": {"currency": "KRW"}, "reason": "r"}                                              | SKU_COST_103
            {"costType": "GOODS", "amount": {"amount": -1, "currency": "KRW"}, "reason": "r"}                                | SKU_COST_104
            {"costType": "GOODS", "amount": {"amount": 1}, "reason": "r"}                                                    | SKU_COST_105
            {"costType": "GOODS", "amount": {"amount": 1, "currency": "KRW"}}                                                | SKU_COST_106
            {"costType": "GOODS", "amount": {"amount": 1, "currency": "KRW"}, "reason": "　 "}                           | SKU_COST_106
            {"costType": "GOODS", "amount": {"amount": 1, "currency": "KRW"}, "reason": "\\u00A0\\u2007\\u202F"}             | SKU_COST_106
            {"costType": "GOODS", "amount": {"amount": 1, "currency": "KRW"}, "reason": "a\\nb"}                             | SKU_COST_108
            {"costType": "GOODS", "amount": {"amount": 1, "currency": "KRW"}, "reason": "r", "effectiveFrom": "1969-12-31T23:59:59Z"} | SKU_COST_109
            {"costType": "RENT", "amount": {"amount": 1, "currency": "KRW"}, "reason": "r"}                                  | PRODUCT_100
            {"costType": "GOODS", "amount": {"amount": 1, "currency": "EUR"}, "reason": "r"}                                 | PRODUCT_100
            """)
        @DisplayName("필드 검증은 400 이고 유스케이스에 닿지 않는다")
        void validation(String body, String code) {
            MvcTestResult result = exchange(HttpMethod.POST, COSTS, body, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verifyNoInteractions(skuCostAdminUseCase);
        }

        @Test
        @DisplayName("사유가 앞뒤 공백을 뺀 뒤 200자를 넘으면 SKU_COST_107 — 200자는 받는다")
        void reasonLength() {
            given(skuCostAdminUseCase.createCost(any())).willReturn(item());
            String ok = "가".repeat(200);
            String tooLong = "가".repeat(201);

            assertThat(exchange(HttpMethod.POST, COSTS, body("  " + ok + "  "), MemberRole.ADMIN)).hasStatus(HttpStatus.CREATED);
            MvcTestResult rejected = exchange(HttpMethod.POST, COSTS, body(tooLong), MemberRole.ADMIN);
            assertThat(rejected).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(rejected).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SKU_COST_107");
        }

        @Test
        @DisplayName("경로 skuId 가 양수가 아니면 400 · SKU_COST_110")
        void skuIdPositive() {
            MvcTestResult result = exchange(HttpMethod.POST, "/api/v1/admin/skus/0/costs", VALID_BODY, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SKU_COST_110");
            verifyNoInteractions(skuCostAdminUseCase);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "EFFECTIVE_FROM_IN_FUTURE, 400, SKU_COST_001",
            "EFFECTIVE_FROM_NOT_AFTER_LATEST, 409, SKU_COST_002",
            "CURRENCY_MISMATCH, 409, SKU_COST_003",
            "COST_CONFLICT, 409, SKU_COST_004",
            "SKU_COST_BUSY, 409, SKU_COST_005",
            "RETROACTIVE_NOT_ALLOWED, 409, SKU_COST_006"
        })
        @DisplayName("도메인 오류는 SkuCostExceptionHandler 가 봉투로 바꾼다")
        void domainErrors(SkuCostErrorCode errorCode, int status, String code) {
            given(skuCostAdminUseCase.createCost(any())).willThrow(new SkuCostException(errorCode, "KRW"));

            MvcTestResult result = exchange(HttpMethod.POST, COSTS, VALID_BODY, MemberRole.ADMIN);

            assertThat(result).hasStatus(status);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        }

        @Test
        @DisplayName("없는 SKU 는 상품 컨텍스트의 404 · PRODUCT_002 다")
        void skuNotFound() {
            given(skuCostAdminUseCase.createCost(any())).willThrow(new ProductException(ProductErrorCode.NOT_FOUND_SKU));

            MvcTestResult result = exchange(HttpMethod.POST, COSTS, VALID_BODY, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_002");
        }
    }

    @Nested
    @DisplayName("조회")
    class Query {

        @Test
        @DisplayName("현재 원가는 200 이고 {skuId, costs[]} 다")
        void current() {
            given(skuCostAdminUseCase.getCurrentCosts(1001L)).willReturn(SkuCurrentCostsAdminResponse.builder().skuId("1001").costs(List.of(item())).build());

            MvcTestResult result = exchange(HttpMethod.GET, COSTS, null, MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.skuId").isEqualTo("1001");
            assertThat(result).bodyJson().extractingPath("$.dataBody.costs[0].costId").isEqualTo("501");
        }

        @Test
        @DisplayName("없는 SKU 의 현재 원가는 404 · PRODUCT_002")
        void currentNotFound() {
            given(skuCostAdminUseCase.getCurrentCosts(1001L)).willThrow(new ProductException(ProductErrorCode.NOT_FOUND_SKU));

            MvcTestResult result = exchange(HttpMethod.GET, COSTS, null, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_002");
        }

        @Test
        @DisplayName("이력은 costType · page · size 를 넘기고, 생략하면 null · 0 · 20 이다")
        void histories() {
            given(skuCostAdminUseCase.getHistories(anyLong(), any(), anyInt(), anyInt()))
                .willReturn(PageResponse.of(new PageImpl<>(List.of(item()), PageRequest.of(0, 20), 1)));

            assertThat(mvc.get().uri(HISTORIES).param("costType", "PACKAGING").param("page", "2").param("size", "50")
                .header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange()).hasStatusOk();
            verify(skuCostAdminUseCase).getHistories(1001L, SkuCostTypeCode.PACKAGING, 2, 50);

            MvcTestResult result = exchange(HttpMethod.GET, HISTORIES, null, MemberRole.ADMIN);
            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].costId").isEqualTo("501");
            verify(skuCostAdminUseCase).getHistories(eq(1001L), isNull(), eq(0), eq(20));
        }

        @ParameterizedTest(name = "{0}={1} → {2}")
        @CsvSource({
            "page, -1, PRODUCT_101",
            "size, 0, PRODUCT_102",
            "size, 101, PRODUCT_102",
            "costType, RENT, PRODUCT_198"
        })
        @DisplayName("이력 파라미터 검증은 400 이고 유스케이스에 닿지 않는다")
        void historyParameters(String name, String value, String code) {
            MvcTestResult result = mvc.get().uri(HISTORIES).param(name, value).header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange();

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            verifyNoInteractions(skuCostAdminUseCase);
        }
    }

    private static SkuCostAdminItem item() {
        return SkuCostAdminItem.builder()
            .costId("501")
            .costType(SkuCostType.GOODS.toMetadata())
            .amount(MoneyItem.of(12_000, "KRW"))
            .effectiveFrom(Instant.parse("2026-10-01T00:00:00Z"))
            .reason("10월 원단 단가 인상")
            .adminMemberId(Long.toString(TestTokens.MEMBER_ID))
            .createdAt(Instant.parse("2026-10-28T09:00:00Z"))
            .build();
    }

    private static String body(String reason) {
        return """
            {"costType": "GOODS", "amount": {"amount": 1, "currency": "KRW"}, "reason": "%s"}
            """.formatted(reason);
    }

    private MvcTestResult exchange(HttpMethod method, String uri, String body, MemberRole role) {
        var request = mvc.method(method).uri(uri);
        if (role != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, token(role));
        }
        if (body != null && method == HttpMethod.POST) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private static String token(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }
}
