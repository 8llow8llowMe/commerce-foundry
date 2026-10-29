package com.commercefoundry.domainlayer.order.adapter.in.web.controller;

import static com.commercefoundry.domainlayer.order.adapter.in.web.controller.OrderQuoteWebControllerTest.assertFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderCreateResponse;
import com.commercefoundry.domainlayer.order.application.command.OrderCreateCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.in.OrderWebUseCase;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 주문 생성 API 의 HTTP 계약 — 인증 · UUID 멱등 키 바인딩(198 · 199) · 201 / 200 · 본문 필드 경로 · 배송지 · 기대 총액 검증. 테스트 값은 모두 가짜다.
 */
@WebMvcTest(OrderWebController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class OrderWebControllerTest {

    private static final String KEY = "3f2b8c1e-6a4d-4e2f-9b1a-7c5d2e8f0a11";
    private static final String ITEMS = "\"items\":[{\"skuId\":\"9004\",\"quantity\":2}]";
    private static final String ADDRESS = "\"shippingAddress\":{\"recipientName\":\"테스트수령인\",\"recipientPhone\":\"010-0000-0001\",\"countryCode\":\"KR\","
        + "\"postalCode\":\"63100\",\"addressLine1\":\"테스트시 테스트로 1\",\"addressLine2\":\" \",\"deliveryMemo\":\"문 앞\"}";
    private static final String BODY = "{" + ITEMS + "," + ADDRESS + "}";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrderWebUseCase orderWebUseCase;

    private static String bearer() {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
    }

    private MvcTestResult create(String key, String body) {
        MockMvcRequestBuilder request = mvc.post().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header(OrderWebController.IDEMPOTENCY_KEY_HEADER, key);
        }
        return request.exchange();
    }

    private static OrderCreateResponse order() {
        return OrderCreateResponse.builder().orderId("4611686018427387905").status(CodeNameDescriptionMetadata.of("PENDING_PAYMENT", "결제 대기", "결제 대기"))
            .paymentDueAt(Instant.parse("2026-10-18T09:30:00Z")).currency("KRW").items(List.of()).build();
    }

    @Test
    @DisplayName("토큰 없이 부르면 401 · SECURITY_001 이고 유스케이스에 닿지 않는다")
    void anonymousIsRejected() {
        assertFailure(mvc.post().uri("/api/v1/orders").header(OrderWebController.IDEMPOTENCY_KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(BODY)
            .exchange(), HttpStatus.UNAUTHORIZED, "SECURITY_001");
        verifyNoInteractions(orderWebUseCase);
    }

    @Test
    void adminIsRejected() {
        String token = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue();
        assertThat(mvc.post().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(OrderWebController.IDEMPOTENCY_KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(BODY).exchange()).hasStatus(403);
        verifyNoInteractions(orderWebUseCase);
    }

    @Test
    @DisplayName("새 주문은 201, 회원은 토큰 주체 · 키는 UUID · 본문은 명령(배송지 공백은 null)으로 넘긴다 — 아이디는 문자열")
    void creates() {
        given(orderWebUseCase.create(anyLong(), any(), any())).willReturn(new OrderWebUseCase.Result(true, order()));

        MvcTestResult result = create(KEY, BODY);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.dataBody.orderId").asString().isEqualTo("4611686018427387905");
        assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("PENDING_PAYMENT");
        ArgumentCaptor<OrderCreateCommand> command = ArgumentCaptor.forClass(OrderCreateCommand.class);
        verify(orderWebUseCase).create(eq(TestTokens.MEMBER_ID), eq(UUID.fromString(KEY)), command.capture());
        assertThat(command.getValue().items().getFirst().skuId()).isEqualTo(9004L);
        assertThat(command.getValue().shippingAddress().recipientPhone()).isEqualTo("01000000001");
        assertThat(command.getValue().shippingAddress().addressLine2()).isNull();
        assertThat(command.getValue().shippingAddress().postalCode()).isEqualTo("63100");
        assertThat(command.getValue().expectedTotalAmount()).isNull();
    }

    @Test
    @DisplayName("같은 키의 재요청(기존 주문)은 200 이다")
    void replayIsOk() {
        given(orderWebUseCase.create(anyLong(), any(), any())).willReturn(new OrderWebUseCase.Result(false, order()));

        assertThat(create(KEY, BODY)).hasStatusOk();
    }

    @Test
    @DisplayName("expectedTotalAmount 를 보내면 Money 로 넘긴다")
    void expectedTotalAmount() {
        given(orderWebUseCase.create(anyLong(), any(), any())).willReturn(new OrderWebUseCase.Result(true, order()));

        create(KEY, "{" + ITEMS + "," + ADDRESS + ",\"expectedTotalAmount\":{\"amount\":81000,\"currency\":\"KRW\"}}");

        ArgumentCaptor<OrderCreateCommand> command = ArgumentCaptor.forClass(OrderCreateCommand.class);
        verify(orderWebUseCase).create(anyLong(), any(), command.capture());
        assertThat(command.getValue().expectedTotalAmount()).isEqualTo(Money.of(81_000, CurrencyCode.KRW));
    }

    @Test
    @DisplayName("Idempotency-Key 가 없으면 400 ORDER_199 · 필드는 헤더 이름, UUID 가 아니면 400 ORDER_198 — 유스케이스에 닿지 않는다")
    void idempotencyKeyBinding() {
        MvcTestResult missing = create(null, BODY);
        assertFailure(missing, HttpStatus.BAD_REQUEST, "ORDER_199");
        assertThat(missing).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("Idempotency-Key");

        assertFailure(create("not-a-uuid", BODY), HttpStatus.BAD_REQUEST, "ORDER_198");
        verifyNoInteractions(orderWebUseCase);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "배송지 없음            | {\"items\":[{\"skuId\":1,\"quantity\":1}]}                     | ORDER_116 | shippingAddress",
        "수령인 없음            | ADDRESS:\"recipientName\":\"테스트수령인\",=                   | ORDER_117 | shippingAddress.recipientName",
        "연락처 없음            | ADDRESS:\"recipientPhone\":\"010-0000-0001\",=                 | ORDER_119 | shippingAddress.recipientPhone",
        "연락처 형식            | ADDRESS:010-0000-0001=010-ABCD                                  | ORDER_120 | shippingAddress.recipientPhone",
        "연락처 괄호            | ADDRESS:010-0000-0001=(010)00000001                              | ORDER_120 | shippingAddress.recipientPhone",
        "국내 연락처 첫 숫자    | ADDRESS:010-0000-0001=11000000001                                | ORDER_120 | shippingAddress.recipientPhone",
        "해외 도시 없음         | ADDRESS:KR=US                                                   | ORDER_138 | shippingAddress.city",
        "KR 우편번호 없음       | ADDRESS:\"postalCode\":\"63100\",=                             | ORDER_123 | shippingAddress.postalCode",
        "주소 없음              | ADDRESS:\"addressLine1\":\"테스트시 테스트로 1\",=             | ORDER_125 | shippingAddress.addressLine1",
        "기대 총액 amount 없음  | EXPECTED:{\"currency\":\"KRW\"}                                 | ORDER_129 | expectedTotalAmount.amount",
        "기대 총액 음수         | EXPECTED:{\"amount\":-1,\"currency\":\"KRW\"}                   | ORDER_130 | expectedTotalAmount.amount",
        "기대 총액 통화 없음    | EXPECTED:{\"amount\":1000}                                      | ORDER_131 | expectedTotalAmount.currency",
        "수량 100               | {\"items\":[{\"skuId\":1,\"quantity\":100}],ADDRESS}            | ORDER_107 | items[0].quantity"
    })
    @DisplayName("본문 검증 오류는 필드별 코드로 400 이다 — 헤더가 있어도 필드 경로가 그대로다(메서드 검증으로 바뀌지 않는다)")
    void validation(String caseName, String spec, String code, String field) {
        MvcTestResult result = create(KEY, body(spec));

        assertFailure(result, HttpStatus.BAD_REQUEST, code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
        verifyNoInteractions(orderWebUseCase);
    }

    /** {@code ADDRESS:from=to} 는 기본 배송지에서 바꾸고, {@code EXPECTED:json} 은 기대 총액을 붙이고, 그 밖에는 {@code ADDRESS} 자리만 채운다. */
    private static String body(String spec) {
        if (spec.startsWith("ADDRESS:")) {
            String[] change = spec.substring("ADDRESS:".length()).split("=", -1);
            return "{" + ITEMS + "," + ADDRESS.replace(change[0], change[1]) + "}";
        }
        if (spec.startsWith("EXPECTED:")) {
            return "{" + ITEMS + "," + ADDRESS + ",\"expectedTotalAmount\":" + spec.substring("EXPECTED:".length()) + "}";
        }
        return spec.replace("ADDRESS", ADDRESS);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "ORDER_AMOUNT_CHANGED, 409, ORDER_010",
        "STOCK_INSUFFICIENT, 409, ORDER_016",
        "STOCK_RESERVATION_INCONSISTENT, 500, ORDER_017",
        "IDEMPOTENCY_IN_PROGRESS, 409, ORDER_018",
        "IDEMPOTENCY_KEY_REUSED, 409, ORDER_019",
        "ORDER_SAVE_FAILED, 500, ORDER_021",
        "PRODUCT_SERVICE_UNAVAILABLE, 503, ORDER_002"
    })
    @DisplayName("도메인 오류는 코드 · 상태 그대로 봉투로 나간다")
    void domainErrors(OrderErrorCode errorCode, int status, String code) {
        given(orderWebUseCase.create(anyLong(), any(), any())).willThrow(new OrderException(errorCode));

        assertFailure(create(KEY, BODY), HttpStatus.valueOf(status), code);
    }
}
