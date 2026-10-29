package com.commercefoundry.domainlayer.skucost.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.item.SkuCostAtInternalItem;
import com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.item.SkuCostInternalItem;
import com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.response.SkuCostAtQueryInternalResponse;
import com.commercefoundry.domainlayer.skucost.application.command.SkuCostAtQueryCommand;
import com.commercefoundry.domainlayer.skucost.application.port.in.SkuCostInternalUseCase;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 서비스 간 시점 원가 조회의 HTTP 계약 — 허용 호출자(operations-service), 본문 검증 코드(SKU_COST_111~117), 정규화(마이크로초 · 중복 합치기 · 순서),
 * 응답 식별자 String · 금액 MoneyItem · 유형 metadata.
 *
 * <p>구성은 {@code SkuInternalControllerTest} 와 같다 — internal 체인 · 사용자 체인을 함께 올리고 인증 주체를 {@code @WithMockUser} 로 넣어
 * 핸들러의 허용 호출자({@code @PreAuthorize})를 본다. 서비스 토큰 서명 검증은 security-core 테스트와 서비스 인가 스모크가 본다.
 */
@WebMvcTest(SkuCostInternalController.class)
@Import({
    InternalResourceServerConfigurer.class,
    InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class SkuCostInternalControllerTest {

    private static final String PATH = "/internal/v1/sku-costs/query";
    private static final String OPERATIONS_SERVICE = "SERVICE_operations-service";
    private static final String VALID_BODY = """
        {"items": [{"skuId": "4611686018427387999", "at": "2026-10-29T01:00:00Z"}]}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private SkuCostInternalUseCase skuCostInternalUseCase;

    @Nested
    @DisplayName("권한 · 응답")
    class Authorization {

        @Test
        @WithMockUser(authorities = OPERATIONS_SERVICE)
        @DisplayName("operations-service 권한이면 200 이고 Response 봉투다 — 식별자는 String, 금액은 {amount, currency}, 유형은 metadata, 빈 costs 는 빈 배열")
        void operationsIsAllowed() {
            given(skuCostInternalUseCase.getCostsAt(any())).willReturn(response());

            MvcTestResult result = post(VALID_BODY);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].skuId").isEqualTo("4611686018427387999");
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].at").isEqualTo("2026-10-29T01:00:00Z");
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].costs[0].costType.code").isEqualTo("GOODS");
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].costs[0].amount.amount").isEqualTo(12000);
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].costs[0].amount.currency").isEqualTo("KRW");
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].costs[0].effectiveFrom").isEqualTo("2026-10-01T00:00:00Z");
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].costs[0].recordedAt").isEqualTo("2026-09-30T09:00:00Z");
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[1].skuId").isEqualTo("9004");
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[1].costs").asArray().isEmpty();
        }

        @Test
        @WithMockUser(authorities = "SERVICE_order-service")
        @DisplayName("order-service 권한이면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다 — 원가는 허용 호출자에게만 나간다")
        void orderServiceIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(post(VALID_BODY).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(skuCostInternalUseCase);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("회원 권한(ROLE_ADMIN)이어도 403 · SECURITY_005 다")
        void memberIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(post(VALID_BODY).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(skuCostInternalUseCase);
        }

        @Test
        @DisplayName("서비스 토큰 없는 익명 요청은 internal 체인이 401 · SECURITY_007 로 막는다")
        void anonymousIsUnauthorized() {
            SecurityEnvelopeAssertions.assertEnvelope(post(VALID_BODY).getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
            verifyNoInteractions(skuCostInternalUseCase);
        }
    }

    @Nested
    @DisplayName("요청 정규화")
    @WithMockUser(authorities = OPERATIONS_SERVICE)
    class Normalization {

        @Test
        @DisplayName("skuId 는 문자열 · 숫자 모두 받고, 시각은 마이크로초로 잘라 같은 쌍을 합친다 — 순서는 처음 나온 순서다")
        void mergesDuplicatesInFirstSeenOrder() {
            given(skuCostInternalUseCase.getCostsAt(any())).willReturn(SkuCostAtQueryInternalResponse.builder().items(List.of()).build());
            String body = """
                {"items": [
                  {"skuId": "9004", "at": "2026-10-29T01:00:00.123456789Z"},
                  {"skuId": 9001, "at": "2026-10-29T01:00:00Z"},
                  {"skuId": 9004, "at": "2026-10-29T01:00:00.123456Z"},
                  {"skuId": "9004", "at": "2026-10-28T01:00:00Z"}
                ]}
                """;

            assertThat(post(body)).hasStatusOk();

            ArgumentCaptor<SkuCostAtQueryCommand> captor = ArgumentCaptor.forClass(SkuCostAtQueryCommand.class);
            verify(skuCostInternalUseCase).getCostsAt(captor.capture());
            assertThat(captor.getValue().points()).containsExactly(
                new SkuCostAtQueryCommand.Point(9004L, Instant.parse("2026-10-29T01:00:00.123456Z")),
                new SkuCostAtQueryCommand.Point(9001L, Instant.parse("2026-10-29T01:00:00Z")),
                new SkuCostAtQueryCommand.Point(9004L, Instant.parse("2026-10-28T01:00:00Z"))
            );
        }

        @Test
        @DisplayName("100개(같은 쌍 100번)는 통과하고 하나로 합친다")
        void hundredItemsPass() {
            given(skuCostInternalUseCase.getCostsAt(any())).willReturn(SkuCostAtQueryInternalResponse.builder().items(List.of()).build());

            assertThat(post(items(100))).hasStatusOk();
            verify(skuCostInternalUseCase).getCostsAt(new SkuCostAtQueryCommand(List.of(
                new SkuCostAtQueryCommand.Point(1L, Instant.parse("2026-10-29T01:00:00Z"))
            )));
        }

        @Test
        @DisplayName("미래 시각도 받는다 — 검증 오류가 아니다")
        void futureAtIsAccepted() {
            given(skuCostInternalUseCase.getCostsAt(any())).willReturn(SkuCostAtQueryInternalResponse.builder().items(List.of()).build());

            assertThat(post("{\"items\": [{\"skuId\": 1, \"at\": \"9999-12-31T23:59:59.999999Z\"}]}")).hasStatusOk();
        }
    }

    @Nested
    @DisplayName("본문 검증")
    @WithMockUser(authorities = OPERATIONS_SERVICE)
    class BodyValidation {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
            items 없음        | {}                                                             | SKU_COST_111 | items               | items 는 필수입니다.
            items 빈 배열      | {"items": []}                                                  | SKU_COST_111 | items               | items 는 필수입니다.
            items 원소 null   | {"items": [null]}                                              | SKU_COST_113 | items[0]            | items 의 원소는 비어 있을 수 없습니다.
            skuId 없음        | {"items": [{"at": "2026-10-29T01:00:00Z"}]}                    | SKU_COST_114 | items[0].skuId      | skuId 는 필수입니다.
            skuId 0          | {"items": [{"skuId": 0, "at": "2026-10-29T01:00:00Z"}]}        | SKU_COST_115 | items[0].skuId      | skuId 는 양수여야 합니다.
            skuId 음수        | {"items": [{"skuId": "-1", "at": "2026-10-29T01:00:00Z"}]}     | SKU_COST_115 | items[0].skuId      | skuId 는 양수여야 합니다.
            at 없음           | {"items": [{"skuId": 1}]}                                      | SKU_COST_116 | items[0].at         | at 은 필수입니다.
            at 1970 이전 | {"items": [{"skuId": 1, "at": "1969-12-31T23:59:59Z"}]} | SKU_COST_117 | items[0].atInRange | at 은 1970-01-01 ~ 9999-12-31 (UTC) 범위여야 합니다.
            at 9999년 초과 | {"items": [{"skuId": 1, "at": "+10000-01-01T00:00:00Z"}]} | SKU_COST_117 | items[0].atInRange | at 은 1970-01-01 ~ 9999-12-31 (UTC) 범위여야 합니다.
            """)
        @DisplayName("필드별 검증 코드")
        void fieldErrors(String name, String body, String code, String field, String message) {
            MvcTestResult result = post(body);

            assertFailure(result, HttpStatus.BAD_REQUEST, code, message);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
            verifyNoInteractions(skuCostInternalUseCase);
        }

        @Test
        @DisplayName("항목 101개(중복 포함 원문 개수)면 400 · SKU_COST_112")
        void tooManyItems() {
            assertFailure(post(items(101)), HttpStatus.BAD_REQUEST, "SKU_COST_112", "items 는 최대 100개입니다.");
            verifyNoInteractions(skuCostInternalUseCase);
        }

        @Test
        @DisplayName("허용되지 않은 호출자도 형식이 틀리면 400 이다 — 메서드 검증이 @PreAuthorize 보다 먼저 돈다")
        @WithMockUser(authorities = "SERVICE_order-service")
        void validationBeforeAuthorization() {
            assertFailure(post("{\"items\": []}"), HttpStatus.BAD_REQUEST, "SKU_COST_111", "items 는 필수입니다.");
            verifyNoInteractions(skuCostInternalUseCase);
        }

        @Test
        @DisplayName("깨진 JSON · 시각 형식 오류는 400 · PRODUCT_100 이다")
        void brokenBody() {
            assertThat(post("{\"items\": [")).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_100");
            MvcTestResult badAt = post("{\"items\": [{\"skuId\": 1, \"at\": \"어제\"}]}");
            assertThat(badAt).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(badAt).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_100");
            verifyNoInteractions(skuCostInternalUseCase);
        }
    }

    private MvcTestResult post(String body) {
        return mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static String items(int count) {
        return "{\"items\": [" + IntStream.rangeClosed(1, count)
            .mapToObj(i -> "{\"skuId\": 1, \"at\": \"2026-10-29T01:00:00Z\"}")
            .collect(Collectors.joining(",")) + "]}";
    }

    private static void assertFailure(MvcTestResult result, HttpStatus status, String code, String message) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo(message);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    private static SkuCostAtQueryInternalResponse response() {
        SkuCostInternalItem goods = SkuCostInternalItem.builder()
            .costType(SkuCostType.GOODS.toMetadata())
            .amount(MoneyItem.of(12_000L, "KRW"))
            .effectiveFrom(Instant.parse("2026-10-01T00:00:00Z"))
            .recordedAt(Instant.parse("2026-09-30T09:00:00Z"))
            .build();
        return SkuCostAtQueryInternalResponse.builder()
            .items(List.of(
                SkuCostAtInternalItem.builder().skuId("4611686018427387999").at(Instant.parse("2026-10-29T01:00:00Z")).costs(List.of(goods)).build(),
                SkuCostAtInternalItem.builder().skuId("9004").at(Instant.parse("2026-10-29T01:00:00Z")).costs(List.of()).build()
            ))
            .build();
    }
}
