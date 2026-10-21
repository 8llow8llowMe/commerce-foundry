package com.commercefoundry.domainlayer.order.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.catalog.application.exception.CatalogErrorCode;
import com.commercefoundry.domainlayer.catalog.application.exception.CatalogException;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderAmountsItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderLineItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderQuoteResponse;
import com.commercefoundry.domainlayer.order.application.command.OrderQuoteCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.in.OrderQuoteWebUseCase;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
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
 * 주문서 미리보기 API 의 HTTP 계약 — 인증 · 봉투 모양 · 검증 오류 코드 · 도메인 오류 변환 · 식별자 문자열. 테스트 값은 모두 가짜다.
 */
@WebMvcTest(OrderQuoteWebController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class OrderQuoteWebControllerTest {

    private static final String DESTINATION = ",\"destination\":{\"countryCode\":\"KR\",\"postalCode\":\"63100\"}";
    private static final String QUOTE_BODY = "{\"items\":[{\"skuId\":\"9004\",\"quantity\":2,\"customOptions\":[{\"fieldKey\":\"chest\",\"value\":\" 96.5 \"}]}]"
        + DESTINATION + "}";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrderQuoteWebUseCase orderQuoteWebUseCase;

    private static String bearer() {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
    }

    private MvcTestResult quote(String body) {
        return mvc.post().uri("/api/v1/orders/quotes").header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    static void assertFailure(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    @Test
    @DisplayName("토큰 없이 부르면 401 · SECURITY_001 이고 유스케이스에 닿지 않는다")
    void anonymousIsRejected() {
        assertFailure(mvc.post().uri("/api/v1/orders/quotes").contentType(MediaType.APPLICATION_JSON).content(QUOTE_BODY).exchange(),
            HttpStatus.UNAUTHORIZED, "SECURITY_001");
        verifyNoInteractions(orderQuoteWebUseCase);
    }

    @Test
    @DisplayName("본문을 명령으로 바꾸고(문자열 skuId · 입력값 공백 자름) 봉투로 내린다 — 아이디는 문자열, 금액은 {amount, currency}")
    void quotes() {
        OrderLineItem line = OrderLineItem.builder().skuId("9004").productId("7301").productName("저고리").optionName("")
            .unitPrice(MoneyItem.of(39_000, "KRW")).quantity(2).lineAmount(MoneyItem.of(78_000, "KRW")).customOrder(true).customOptions(List.of()).build();
        OrderAmountsItem amounts = OrderAmountsItem.builder()
            .itemsAmount(MoneyItem.of(78_000, "KRW")).shippingFee(MoneyItem.of(0, "KRW")).totalAmount(MoneyItem.of(78_000, "KRW")).build();
        given(orderQuoteWebUseCase.quote(any())).willReturn(OrderQuoteResponse.builder().currency("KRW").items(List.of(line)).amounts(amounts).build());

        MvcTestResult result = quote(QUOTE_BODY);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].skuId").asString().isEqualTo("9004");
        assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].productId").asString().isEqualTo("7301");
        assertThat(result).bodyJson().extractingPath("$.dataBody.amounts.totalAmount.amount").asNumber().isEqualTo(78_000);
        ArgumentCaptor<OrderQuoteCommand> command = ArgumentCaptor.forClass(OrderQuoteCommand.class);
        verify(orderQuoteWebUseCase).quote(command.capture());
        assertThat(command.getValue().items().getFirst().skuId()).isEqualTo(9004L);
        assertThat(command.getValue().items().getFirst().customOptions().values().getFirst().value()).isEqualTo("96.5");
        assertThat(command.getValue().postalCode()).isEqualTo("63100");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "items 없음             | {\"destination\":{\"countryCode\":\"KR\",\"postalCode\":\"63100\"}}                                | ORDER_101 | items",
        "items 빈 배열          | {\"items\":[],\"destination\":{\"countryCode\":\"KR\",\"postalCode\":\"63100\"}}                    | ORDER_101 | items",
        "items null 원소        | {\"items\":[null],\"destination\":{\"countryCode\":\"KR\",\"postalCode\":\"63100\"}}                | ORDER_103 | items[0]",
        "skuId 없음             | {\"items\":[{\"quantity\":1}],\"destination\":{\"countryCode\":\"KR\",\"postalCode\":\"63100\"}}    | ORDER_104 | items[0].skuId",
        "skuId 0                | {\"items\":[{\"skuId\":0,\"quantity\":1}],\"destination\":{\"countryCode\":\"KR\",\"postalCode\":\"63100\"}} | ORDER_105 | items[0].skuId",
        "quantity 100           | {\"items\":[{\"skuId\":1,\"quantity\":100}],\"destination\":{\"countryCode\":\"KR\",\"postalCode\":\"63100\"}} | ORDER_107 | items[0].quantity",
        "destination 없음       | {\"items\":[{\"skuId\":1,\"quantity\":1}]}                                                          | ORDER_116 | destination",
        "국가 소문자            | {\"items\":[{\"skuId\":1,\"quantity\":1}],\"destination\":{\"countryCode\":\"kr\",\"postalCode\":\"63100\"}} | ORDER_122 | destination.countryCode",
        "KR 우편번호 없음       | {\"items\":[{\"skuId\":1,\"quantity\":1}],\"destination\":{\"countryCode\":\"KR\"}}                  | ORDER_123 | destination.postalCode",
        "KR 우편번호 4자리      | {\"items\":[{\"skuId\":1,\"quantity\":1}],\"destination\":{\"countryCode\":\"KR\",\"postalCode\":\"6310\"}} | ORDER_124 | destination.postalCode",
        "해외 우편번호 21자     | {\"items\":[{\"skuId\":1,\"quantity\":1}],\"destination\":{\"countryCode\":\"US\",\"postalCode\":\"123456789012345678901\"}} | ORDER_124 | destination.postalCode",
        "깨진 JSON              | {\"items\":                                                                                         | ORDER_100 | request"
    })
    @DisplayName("검증 오류는 필드별 코드로 400 이다 — 유스케이스에 닿지 않는다")
    void validation(String caseName, String body, String code, String field) {
        MvcTestResult result = quote(body);

        assertFailure(result, HttpStatus.BAD_REQUEST, code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
        verifyNoInteractions(orderQuoteWebUseCase);
    }

    @Test
    @DisplayName("해외 우편번호는 선택이다 — 없어도 받는다")
    void internationalPostalCodeOptional() {
        given(orderQuoteWebUseCase.quote(any())).willReturn(OrderQuoteResponse.builder().items(List.of()).build());

        assertThat(quote("{\"items\":[{\"skuId\":1,\"quantity\":1}],\"destination\":{\"countryCode\":\"US\"}}")).hasStatusOk();
        ArgumentCaptor<OrderQuoteCommand> command = ArgumentCaptor.forClass(OrderQuoteCommand.class);
        verify(orderQuoteWebUseCase).quote(command.capture());
        assertThat(command.getValue().postalCode()).isNull();
    }

    @Test
    @DisplayName("같은 SKU · 같은 맞춤 입력 줄의 중복은 요청 검증이 아니라 주문서 계산이 판정한다(ORDER_008, 줄 이름으로 알린다) — 본문은 유스케이스까지 간다")
    void duplicateLinesAreJudgedByQuote() {
        String duplicated = "{\"items\":[{\"skuId\":1,\"quantity\":1,\"customOptions\":[{\"fieldKey\":\"chest\",\"value\":\"96\"}]},"
            + "{\"skuId\":1,\"quantity\":2,\"customOptions\":[{\"fieldKey\":\"chest\",\"value\":\" 96 \"}]}]" + DESTINATION + "}";
        given(orderQuoteWebUseCase.quote(any())).willThrow(new OrderException(OrderErrorCode.DUPLICATE_LINE, "저고리(아이보리)"));

        MvcTestResult result = quote(duplicated);

        assertFailure(result, HttpStatus.BAD_REQUEST, "ORDER_008");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("같은 상품이 주문에 중복되어 있습니다: 저고리(아이보리)");
    }

    @Test
    @DisplayName("items 51개 ORDER_102 · 입력 키 중복 ORDER_110 · 정규화 뒤 500자 초과 ORDER_114")
    void sizes() {
        String items = IntStream.rangeClosed(1, 51).mapToObj(index -> "{\"skuId\":" + index + ",\"quantity\":1}").collect(Collectors.joining(","));
        assertFailure(quote("{\"items\":[" + items + "]" + DESTINATION + "}"), HttpStatus.BAD_REQUEST, "ORDER_102");
        assertFailure(quote("{\"items\":[{\"skuId\":1,\"quantity\":1,\"customOptions\":[{\"fieldKey\":\"a\",\"value\":\"1\"},{\"fieldKey\":\"a\",\"value\":\"2\"}]}]"
            + DESTINATION + "}"), HttpStatus.BAD_REQUEST, "ORDER_110");
        assertFailure(quote("{\"items\":[{\"skuId\":1,\"quantity\":1,\"customOptions\":[{\"fieldKey\":\"memo\",\"value\":\"" + "가".repeat(501) + "\"}]}]"
            + DESTINATION + "}"), HttpStatus.BAD_REQUEST, "ORDER_114");
        verifyNoInteractions(orderQuoteWebUseCase);
    }

    @Test
    @DisplayName("원문은 500자 이하여도 NFC 정규화로 늘어나 500자를 넘으면 ORDER_114 다 — 컬럼 길이 초과가 500 이 되지 않게")
    void normalizedLength() {
        // U+0958(데바나가리 QA)은 NFC 에서 두 글자(U+0915 U+093C)로 풀린다 — 원문 300자 → 저장 600자
        String expanding = "\\u0958".repeat(300);

        assertFailure(quote("{\"items\":[{\"skuId\":1,\"quantity\":1,\"customOptions\":[{\"fieldKey\":\"memo\",\"value\":\"" + expanding + "\"}]}]"
            + DESTINATION + "}"), HttpStatus.BAD_REQUEST, "ORDER_114");
        verifyNoInteractions(orderQuoteWebUseCase);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "ITEM_NOT_PURCHASABLE, 409, ORDER_003",
        "CUSTOM_OPTION_NOT_ALLOWED, 400, ORDER_004",
        "QUANTITY_EXCEEDED, 409, ORDER_005",
        "CURRENCY_MIXED, 409, ORDER_006",
        "INTERNATIONAL_SHIPPING_INFO_MISSING, 409, ORDER_007",
        "DUPLICATE_LINE, 400, ORDER_008",
        "SHIPPING_UNAVAILABLE, 409, ORDER_012",
        "DESTINATION_CURRENCY_NOT_SUPPORTED, 400, ORDER_014",
        "DELIVERY_SERVICE_UNAVAILABLE, 503, ORDER_011",
        "DESTINATION_NOT_SUPPORTED, 400, ORDER_013",
        "SHIPPING_WEIGHT_NOT_SUPPORTED, 400, ORDER_015"
    })
    @DisplayName("도메인 오류는 코드 · 상태 그대로 봉투로 나간다")
    void domainErrors(OrderErrorCode errorCode, int status, String code) {
        given(orderQuoteWebUseCase.quote(any())).willThrow(new OrderException(errorCode, "저고리(아이보리)"));

        assertFailure(quote(QUOTE_BODY), HttpStatus.valueOf(status), code);
    }

    @Test
    @DisplayName("SKU 조회 실패(CATALOG_001)는 주문 계약대로 ORDER_002(503) 로 나간다")
    void catalogFailure() {
        given(orderQuoteWebUseCase.quote(any())).willThrow(new CatalogException(CatalogErrorCode.PRODUCT_SERVICE_UNAVAILABLE));

        MvcTestResult result = quote(QUOTE_BODY);

        assertFailure(result, HttpStatus.SERVICE_UNAVAILABLE, "ORDER_002");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE.getMessage());
    }
}
