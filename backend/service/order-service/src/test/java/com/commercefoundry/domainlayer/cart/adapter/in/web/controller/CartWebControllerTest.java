package com.commercefoundry.domainlayer.cart.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.item.CartLineItem;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.item.CartMergeSkippedItem;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.item.CustomOptionItem;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response.CartMergeResponse;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response.CartResponse;
import com.commercefoundry.domainlayer.cart.application.command.CartItemAddCommand;
import com.commercefoundry.domainlayer.cart.application.command.CartMergeCommand;
import com.commercefoundry.domainlayer.cart.application.exception.CartErrorCode;
import com.commercefoundry.domainlayer.cart.application.exception.CartException;
import com.commercefoundry.domainlayer.cart.application.port.in.CartWebUseCase;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOption;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestTokens;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 고객 장바구니 API 의 HTTP 계약 — 인증 · 봉투 모양 · 검증 오류 코드 · 도메인 오류 변환. 슬라이스라 {@code *BeansConfig} 가 빠지므로 보안 구성을 직접 import 한다
 * ({@code bootstrap-conventions.md} 보안 스모크 테스트). 토큰은 컨텍스트의 공개키와 한 쌍인 테스트 발급기로 만든다.
 */
@WebMvcTest(CartWebController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class CartWebControllerTest {

    private static final long MEMBER_ID = TestTokens.MEMBER_ID;
    private static final CartResponse EMPTY_CART = CartResponse.builder().items(List.of()).totalAmount(null).purchasableCount(0).build();

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CartWebUseCase cartWebUseCase;

    private static String bearer() {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
    }

    private MvcTestResult post(String uri, String body) {
        return mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult patch(String uri, String body) {
        return mvc.patch().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static void assertFailure(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    @Nested
    @DisplayName("인증")
    class Authentication {

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource(delimiter = '|', value = {
            "GET    | /api/v1/carts/me         | {}",
            "POST   | /api/v1/carts/me/items   | {\"skuId\":1,\"quantity\":1}",
            "PATCH  | /api/v1/carts/me/items/1 | {\"quantity\":1}",
            "DELETE | /api/v1/carts/me/items/1 | {}",
            "POST   | /api/v1/carts/me/merge   | {\"items\":[]}"
        })
        @DisplayName("토큰 없이 부르면 401 · SECURITY_001 이고 유스케이스에 닿지 않는다")
        void anonymousIsRejected(String method, String uri, String body) {
            MvcTestResult result = mvc.method(HttpMethod.valueOf(method)).uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();

            assertFailure(result, HttpStatus.UNAUTHORIZED, "SECURITY_001");
            verifyNoInteractions(cartWebUseCase);
        }

        /**
         * 서비스 URL 규칙은 열려 있고(default-allow) 인증 요구는 {@code @PreAuthorize} 가 한다. 본문 검증({@code @Valid})은 인자 바인딩 단계라 메서드 보안보다 먼저 돈다 —
         * 익명이라도 본문 형식이 틀리면 400 이다. 형식 오류 응답에는 회원 데이터가 없고, 유스케이스(DB · 원격)에는 닿지 않는다.
         */
        @Test
        @DisplayName("익명 요청이라도 본문 형식이 틀리면 메서드 보안보다 먼저 400 이다 — 유스케이스에는 닿지 않는다")
        void anonymousInvalidBodyIsBadRequest() {
            MvcTestResult result = mvc.post().uri("/api/v1/carts/me/items").contentType(MediaType.APPLICATION_JSON).content("{}").exchange();

            assertFailure(result, HttpStatus.BAD_REQUEST, "CART_101");
            verifyNoInteractions(cartWebUseCase);
        }

        @Test
        @DisplayName("회원은 토큰의 주체로만 정한다 — 유스케이스에 JWT 의 memberId 가 간다")
        void memberFromToken() {
            given(cartWebUseCase.getCart(MEMBER_ID)).willReturn(EMPTY_CART);

            MvcTestResult result = mvc.get().uri("/api/v1/carts/me").header(HttpHeaders.AUTHORIZATION, bearer()).exchange();

            assertThat(result).hasStatusOk();
            verify(cartWebUseCase).getCart(MEMBER_ID);
        }
    }

    @Test
    @DisplayName("조회는 Response 봉투다 — 아이디는 문자열, 금액은 {amount, currency}, 상태는 metadata, 빈 합계는 null")
    void cartEnvelope() {
        CartLineItem line = CartLineItem.builder()
            .itemId("4611686018427387999").skuId("9004").productId("7301").productName("저고리").optionName("아이보리 / L").quantity(2)
            .unitPrice(MoneyItem.of(39_000, "KRW")).lineAmount(MoneyItem.of(78_000, "KRW"))
            .status(CodeNameDescriptionMetadata.of("ON_SALE", "구매 가능", "지금 주문할 수 있습니다.")).maxQuantityPerOrder(null).customOrder(true)
            .customOptions(List.of(CustomOptionItem.builder().fieldKey("chest").value("96").build())).quantityExceeded(false)
            .build();
        given(cartWebUseCase.getCart(MEMBER_ID)).willReturn(CartResponse.builder().items(List.of(line)).totalAmount(MoneyItem.of(78_000, "KRW")).purchasableCount(1).build());

        MvcTestResult result = mvc.get().uri("/api/v1/carts/me").header(HttpHeaders.AUTHORIZATION, bearer()).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].itemId").asString().isEqualTo("4611686018427387999");
        assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].skuId").asString().isEqualTo("9004");
        assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].unitPrice.amount").asNumber().isEqualTo(39_000);
        assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].lineAmount.currency").asString().isEqualTo("KRW");
        assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].status.code").asString().isEqualTo("ON_SALE");
        assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].maxQuantityPerOrder").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].customOrder").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].quantityExceeded").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].customOptions[0].fieldKey").asString().isEqualTo("chest");
        assertThat(result).bodyJson().extractingPath("$.dataBody.totalAmount.amount").asNumber().isEqualTo(78_000);
        assertThat(result).bodyJson().extractingPath("$.dataBody.purchasableCount").asNumber().isEqualTo(1);

        given(cartWebUseCase.getCart(MEMBER_ID)).willReturn(EMPTY_CART);
        MvcTestResult empty = mvc.get().uri("/api/v1/carts/me").header(HttpHeaders.AUTHORIZATION, bearer()).exchange();
        assertThat(empty).bodyJson().extractingPath("$.dataBody.items").asArray().isEmpty();
        assertThat(empty).bodyJson().extractingPath("$.dataBody.totalAmount").isNull();
    }

    @Nested
    @DisplayName("담기")
    class AddItem {

        @Test
        @DisplayName("본문을 명령으로 바꾼다 — 문자열 skuId 도 받고, 입력값 앞뒤 공백은 자른다")
        void toCommand() {
            given(cartWebUseCase.addItem(anyLong(), any())).willReturn(EMPTY_CART);

            MvcTestResult result = post("/api/v1/carts/me/items",
                "{\"skuId\":\"9004\",\"quantity\":2,\"customOptions\":[{\"fieldKey\":\"chest\",\"value\":\" 96.5 \"}]}");

            assertThat(result).hasStatusOk();
            ArgumentCaptor<CartItemAddCommand> command = ArgumentCaptor.forClass(CartItemAddCommand.class);
            verify(cartWebUseCase).addItem(eq(MEMBER_ID), command.capture());
            assertThat(command.getValue().skuId()).isEqualTo(9004L);
            assertThat(command.getValue().quantity()).isEqualTo(2);
            assertThat(command.getValue().customOptions().values()).extracting(CustomOption::value).containsExactly("96.5");
        }

        @Test
        @DisplayName("customOptions 가 없거나 null 이면 빈 입력이다")
        void noCustomOptions() {
            given(cartWebUseCase.addItem(anyLong(), any())).willReturn(EMPTY_CART);

            assertThat(post("/api/v1/carts/me/items", "{\"skuId\":9004,\"quantity\":1,\"customOptions\":null}")).hasStatusOk();
            ArgumentCaptor<CartItemAddCommand> command = ArgumentCaptor.forClass(CartItemAddCommand.class);
            verify(cartWebUseCase).addItem(eq(MEMBER_ID), command.capture());
            assertThat(command.getValue().customOptions().isEmpty()).isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "skuId 없음                 | {\"quantity\":1}                                                                   | CART_101 | skuId",
            "skuId 0                    | {\"skuId\":0,\"quantity\":1}                                                       | CART_102 | skuId",
            "quantity 없음              | {\"skuId\":1}                                                                      | CART_103 | quantity",
            "quantity 0                 | {\"skuId\":1,\"quantity\":0}                                                       | CART_104 | quantity",
            "quantity 100               | {\"skuId\":1,\"quantity\":100}                                                     | CART_104 | quantity",
            "입력 칸 null               | {\"skuId\":1,\"quantity\":1,\"customOptions\":[null]}                              | CART_106 | customOptions[0]",
            "fieldKey 중복 | {\"skuId\":1,\"quantity\":1,\"customOptions\":[{\"fieldKey\":\"a\",\"value\":\"1\"},{\"fieldKey\":\"a\",\"value\":\"2\"}]} | CART_107 | customOptions",
            "fieldKey 없음              | {\"skuId\":1,\"quantity\":1,\"customOptions\":[{\"value\":\"1\"}]}                 | CART_108 | customOptions[0].fieldKey",
            "fieldKey 대문자 시작       | {\"skuId\":1,\"quantity\":1,\"customOptions\":[{\"fieldKey\":\"Chest\",\"value\":\"1\"}]} | CART_109 | customOptions[0].fieldKey",
            "value 공백                 | {\"skuId\":1,\"quantity\":1,\"customOptions\":[{\"fieldKey\":\"chest\",\"value\":\"  \"}]} | CART_110 | customOptions[0].value",
            "숫자 자리에 문자           | {\"skuId\":1,\"quantity\":\"a\"}                                                   | CART_100 | quantity",
            "깨진 JSON                  | {\"skuId\":                                                                        | CART_100 | request"
        })
        @DisplayName("검증 오류는 필드별 코드로 400 이고 유스케이스에 닿지 않는다")
        void validation(String caseName, String body, String code, String field) {
            MvcTestResult result = post("/api/v1/carts/me/items", body);

            assertFailure(result, HttpStatus.BAD_REQUEST, code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
            verifyNoInteractions(cartWebUseCase);
        }

        @Test
        @DisplayName("value 500자 초과 · 입력 21개는 400 이다")
        void sizeLimits() {
            String longValue = "가".repeat(501);
            assertFailure(post("/api/v1/carts/me/items", "{\"skuId\":1,\"quantity\":1,\"customOptions\":[{\"fieldKey\":\"memo\",\"value\":\"" + longValue + "\"}]}"),
                HttpStatus.BAD_REQUEST, "CART_111");

            String options = IntStream.rangeClosed(1, 21).mapToObj(index -> "{\"fieldKey\":\"f" + index + "\",\"value\":\"1\"}").collect(Collectors.joining(","));
            assertFailure(post("/api/v1/carts/me/items", "{\"skuId\":1,\"quantity\":1,\"customOptions\":[" + options + "]}"), HttpStatus.BAD_REQUEST, "CART_105");
            verifyNoInteractions(cartWebUseCase);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "CART_SKU_NOT_FOUND, 404, CART_002",
            "CART_SKU_NOT_PURCHASABLE, 409, CART_003",
            "CART_CUSTOM_OPTION_NOT_ALLOWED, 400, CART_004",
            "CART_ITEM_LIMIT_EXCEEDED, 409, CART_006",
            "CART_CONCURRENTLY_MODIFIED, 409, CART_007",
            "CART_PRODUCT_SERVICE_UNAVAILABLE, 503, CART_008"
        })
        @DisplayName("도메인 오류는 코드 · 상태 그대로 봉투로 나간다")
        void domainErrors(CartErrorCode errorCode, int status, String code) {
            given(cartWebUseCase.addItem(anyLong(), any())).willThrow(new CartException(errorCode, 50));

            assertFailure(post("/api/v1/carts/me/items", "{\"skuId\":1,\"quantity\":1}"), HttpStatus.valueOf(status), code);
        }

        @Test
        @DisplayName("수량 상한 초과 메시지에는 상한이 들어 있다")
        void quantityExceededMessage() {
            given(cartWebUseCase.addItem(anyLong(), any())).willThrow(new CartException(CartErrorCode.CART_QUANTITY_EXCEEDED, 10));

            MvcTestResult result = post("/api/v1/carts/me/items", "{\"skuId\":1,\"quantity\":1}");

            assertFailure(result, HttpStatus.CONFLICT, "CART_005");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("한 상품은 최대 10개까지 담을 수 있습니다.");
        }
    }

    @Nested
    @DisplayName("수량 변경 · 삭제")
    class ChangeAndRemove {

        @Test
        @DisplayName("경로의 줄 아이디와 JWT 회원으로 바꾼다")
        void changes() {
            given(cartWebUseCase.changeQuantity(MEMBER_ID, 77L, 3)).willReturn(EMPTY_CART);

            assertThat(patch("/api/v1/carts/me/items/77", "{\"quantity\":3}")).hasStatusOk();
            verify(cartWebUseCase).changeQuantity(MEMBER_ID, 77L, 3);
        }

        @Test
        @DisplayName("수량 범위 밖 400 · 줄 아이디가 숫자가 아니면 CART_198")
        void validation() {
            assertFailure(patch("/api/v1/carts/me/items/77", "{\"quantity\":100}"), HttpStatus.BAD_REQUEST, "CART_104");
            assertFailure(patch("/api/v1/carts/me/items/77", "{}"), HttpStatus.BAD_REQUEST, "CART_103");
            assertFailure(patch("/api/v1/carts/me/items/abc", "{\"quantity\":1}"), HttpStatus.BAD_REQUEST, "CART_198");
            verifyNoInteractions(cartWebUseCase);
        }

        @Test
        @DisplayName("없거나 다른 회원의 줄은 404 · CART_001 이다")
        void notFound() {
            given(cartWebUseCase.removeItem(MEMBER_ID, 77L)).willThrow(new CartException(CartErrorCode.CART_ITEM_NOT_FOUND));

            MvcTestResult result = mvc.delete().uri("/api/v1/carts/me/items/77").header(HttpHeaders.AUTHORIZATION, bearer()).exchange();

            assertFailure(result, HttpStatus.NOT_FOUND, "CART_001");
        }
    }

    @Nested
    @DisplayName("병합")
    class Merge {

        @Test
        @DisplayName("항목을 명령으로 바꾸고, 건너뛴 항목은 {index(숫자), skuId(문자열), reason(문자열)} 배열로 나간다")
        void merges() {
            CartMergeSkippedItem skipped = CartMergeSkippedItem.builder().index(1).skuId("9005").reason("NOT_PURCHASABLE").build();
            given(cartWebUseCase.merge(anyLong(), any())).willReturn(CartMergeResponse.builder().cart(EMPTY_CART).skippedItems(List.of(skipped)).build());

            MvcTestResult result = post("/api/v1/carts/me/merge", "{\"items\":[{\"skuId\":9004,\"quantity\":2},{\"skuId\":9005,\"quantity\":1}]}");

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.skippedItems[0].index").asNumber().isEqualTo(1);
            assertThat(result).bodyJson().extractingPath("$.dataBody.skippedItems[0].skuId").asString().isEqualTo("9005");
            assertThat(result).bodyJson().extractingPath("$.dataBody.skippedItems[0].reason").asString().isEqualTo("NOT_PURCHASABLE");
            assertThat(result).bodyJson().extractingPath("$.dataBody.cart.items").asArray().isEmpty();
            ArgumentCaptor<CartMergeCommand> command = ArgumentCaptor.forClass(CartMergeCommand.class);
            verify(cartWebUseCase).merge(eq(MEMBER_ID), command.capture());
            assertThat(command.getValue().items()).extracting(CartItemAddCommand::skuId).containsExactly(9004L, 9005L);
        }

        @Test
        @DisplayName("items 없음 CART_112 · 51개 CART_113 · null 항목 CART_114 · 항목 필드 오류는 items[i].필드")
        void validation() {
            assertFailure(post("/api/v1/carts/me/merge", "{}"), HttpStatus.BAD_REQUEST, "CART_112");

            String items = IntStream.rangeClosed(1, 51).mapToObj(index -> "{\"skuId\":" + index + ",\"quantity\":1}").collect(Collectors.joining(","));
            assertFailure(post("/api/v1/carts/me/merge", "{\"items\":[" + items + "]}"), HttpStatus.BAD_REQUEST, "CART_113");
            assertFailure(post("/api/v1/carts/me/merge", "{\"items\":[null]}"), HttpStatus.BAD_REQUEST, "CART_114");

            MvcTestResult nested = post("/api/v1/carts/me/merge", "{\"items\":[{\"skuId\":1,\"quantity\":0}]}");
            assertFailure(nested, HttpStatus.BAD_REQUEST, "CART_104");
            assertThat(nested).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("items[0].quantity");
            verifyNoInteractions(cartWebUseCase);
        }
    }
}
