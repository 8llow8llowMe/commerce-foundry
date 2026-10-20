package com.commercefoundry.domainlayer.product.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.StockRestorationItemInternalItem;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockRestorationResponse;
import com.commercefoundry.domainlayer.product.application.command.StockRestorationCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.StockInternalUseCase;
import com.commercefoundry.domainlayer.product.domain.model.StockRestorationReason;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * 서비스 간 결제 후 재고 복원의 HTTP 계약 — 권한, 본문 검증 코드, 응답 식별자 String, 도메인 오류 봉투.
 * 구성은 {@link StockInternalControllerTest} 와 같다.
 */
@WebMvcTest(StockRestorationInternalController.class)
@Import({
    InternalResourceServerConfigurer.class,
    InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class StockRestorationInternalControllerTest {

    private static final String PATH = "/internal/v1/stock-restorations";
    private static final String ORDER_SERVICE = "SERVICE_order-service";
    private static final long CLAIM_ID = 4611686018427388201L;
    private static final long RESERVATION_ID = 4611686018427388001L;
    private static final String VALID_BODY = """
        {"claimId": 4611686018427388201, "reservationId": 4611686018427388001, "reason": "CANCELLATION",
         "items": [{"skuId": 9004, "quantity": 2}, {"skuId": 9001, "quantity": 1}]}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private StockInternalUseCase stockInternalUseCase;

    @Nested
    @DisplayName("권한 · 응답")
    class Authorization {

        @Test
        @WithMockUser(authorities = ORDER_SERVICE)
        @DisplayName("order-service 권한이면 200 이고 Response 봉투다 — 식별자는 String, 사유는 제어 코드 문자열")
        void restoreIsAllowed() {
            StockRestorationCommand command = new StockRestorationCommand(CLAIM_ID, RESERVATION_ID, StockRestorationReason.CANCELLATION, List.of(
                new StockRestorationCommand.Item(9004L, 2), new StockRestorationCommand.Item(9001L, 1)
            ));
            given(stockInternalUseCase.restore(command)).willReturn(response());

            MvcTestResult result = post(VALID_BODY);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.restorationId").isEqualTo("4611686018427388101");
            assertThat(result).bodyJson().extractingPath("$.dataBody.claimId").isEqualTo("4611686018427388201");
            assertThat(result).bodyJson().extractingPath("$.dataBody.reservationId").isEqualTo("4611686018427388001");
            assertThat(result).bodyJson().extractingPath("$.dataBody.reason").isEqualTo("CANCELLATION");
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].skuId").isEqualTo("9001");
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].quantity").isEqualTo(1);
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].restored").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[1].restored").isEqualTo(false);
            verify(stockInternalUseCase).restore(command);
        }

        @Test
        @WithMockUser(authorities = "SERVICE_payment-service")
        @DisplayName("다른 서비스 권한이면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void otherServiceIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(post(VALID_BODY).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(stockInternalUseCase);
        }

        @Test
        @WithMockUser(roles = "CUSTOMER")
        @DisplayName("회원 권한(ROLE_CUSTOMER)이면 403 · SECURITY_005 다")
        void memberIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(post(VALID_BODY).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(stockInternalUseCase);
        }
    }

    @Nested
    @DisplayName("도메인 오류 봉투")
    @WithMockUser(authorities = ORDER_SERVICE)
    class DomainErrors {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
            NOT_FOUND_STOCK_RESERVATION                 | 404 | PRODUCT_003 | 존재하지 않는 재고 선점입니다.
            STOCK_RESTORATION_RESERVATION_NOT_CONFIRMED | 409 | PRODUCT_027 | 확정된 재고 선점만 복원할 수 있습니다.
            STOCK_RESTORATION_MISMATCH                  | 409 | PRODUCT_029 | 이 클레임에는 내용이 다른 재고 복원이 이미 있습니다.
            STOCK_RESTORATION_CONFLICT                  | 409 | PRODUCT_030 | 재고 복원이 동시에 요청되었습니다. 다시 시도해 주세요.
            STOCK_RESERVATION_LOCK_TIMEOUT              | 409 | PRODUCT_031 | 같은 선점을 처리 중입니다. 다시 시도해 주세요.
            """)
        @DisplayName("업무 오류 코드")
        void errors(ProductErrorCode errorCode, int status, String code, String message) {
            given(stockInternalUseCase.restore(any())).willThrow(new ProductException(errorCode));

            assertFailure(post(VALID_BODY), HttpStatus.valueOf(status), code, message);
        }

        @Test
        @DisplayName("상한 초과는 409 · PRODUCT_028 이고 메시지에 넘은 SKU 아이디가 실린다")
        void exceeded() {
            given(stockInternalUseCase.restore(any()))
                .willThrow(new ProductException(ProductErrorCode.STOCK_RESTORATION_QUANTITY_EXCEEDED, List.of(9001L, 9999L)));

            assertFailure(post(VALID_BODY), HttpStatus.CONFLICT, "PRODUCT_028", "복원 가능 수량을 넘은 SKU 가 있습니다: [9001, 9999]");
        }
    }

    @Nested
    @DisplayName("본문 검증")
    @WithMockUser(authorities = ORDER_SERVICE)
    class BodyValidation {

        /** 본문 표의 {@code $HEAD} — 헤더 칸이 모두 맞는 값. */
        private static final String HEAD = "\"claimId\": 1, \"reservationId\": 1, \"reason\": \"RETURN\"";
        /** 본문 표의 {@code $ITEMS} — 항목 하나가 맞는 값. */
        private static final String ITEMS = "\"items\": [{\"skuId\": 1, \"quantity\": 1}]";

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
            claimId 없음        | {"reservationId": 1, "reason": "RETURN", $ITEMS}                 | PRODUCT_127 | claimId           | claimId 는 필수입니다.
            claimId 0          | {"claimId": 0, "reservationId": 1, "reason": "RETURN", $ITEMS}   | PRODUCT_128 | claimId           | claimId 는 양수여야 합니다.
            reservationId 없음  | {"claimId": 1, "reason": "RETURN", $ITEMS}                       | PRODUCT_129 | reservationId     | reservationId 는 필수입니다.
            reservationId 음수  | {"claimId": 1, "reservationId": -1, "reason": "RETURN", $ITEMS}  | PRODUCT_121 | reservationId     | reservationId 는 양수여야 합니다.
            reason 없음         | {"claimId": 1, "reservationId": 1, $ITEMS}                       | PRODUCT_130 | reason            | reason 은 필수입니다.
            items 없음          | {$HEAD}                                                          | PRODUCT_114 | items             | items 는 필수입니다.
            items 빈 배열        | {$HEAD, "items": []}                                             | PRODUCT_114 | items             | items 는 필수입니다.
            items 원소 null     | {$HEAD, "items": [null]}                                         | PRODUCT_116 | items[0]          | items 의 원소는 비어 있을 수 없습니다.
            skuId 없음          | {$HEAD, "items": [{"quantity": 1}]}                              | PRODUCT_117 | items[0].skuId    | skuId 는 필수입니다.
            skuId 0            | {$HEAD, "items": [{"skuId": 0, "quantity": 1}]}                  | PRODUCT_118 | items[0].skuId    | skuId 는 양수여야 합니다.
            quantity 없음       | {$HEAD, "items": [{"skuId": 1}]}                                 | PRODUCT_119 | items[0].quantity | quantity 는 필수입니다.
            quantity 0         | {$HEAD, "items": [{"skuId": 1, "quantity": 0}]}                  | PRODUCT_120 | items[0].quantity | quantity 는 1 이상이어야 합니다.
            quantity 상한 초과    | {$HEAD, "items": [{"skuId": 1, "quantity": 10001}]}              | PRODUCT_122 | items[0].quantity | quantity 는 10000 이하여야 합니다.
            """)
        @DisplayName("필드별 검증 코드")
        void fieldErrors(String name, String bodyTemplate, String code, String field, String message) {
            MvcTestResult result = post(bodyTemplate.replace("$HEAD", HEAD).replace("$ITEMS", ITEMS));

            assertFailure(result, HttpStatus.BAD_REQUEST, code, message);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
            verifyNoInteractions(stockInternalUseCase);
        }

        @Test
        @DisplayName("항목 101개(중복 포함 원문 개수)면 400 · PRODUCT_115")
        void tooManyItems() {
            String items = IntStream.rangeClosed(1, 101).mapToObj(i -> "{\"skuId\": 1, \"quantity\": 1}").collect(Collectors.joining(","));

            MvcTestResult result = post("{\"claimId\": 1, \"reservationId\": 1, \"reason\": \"RETURN\", \"items\": [" + items + "]}");

            assertFailure(result, HttpStatus.BAD_REQUEST, "PRODUCT_115", "items 는 최대 100개입니다.");
            verifyNoInteractions(stockInternalUseCase);
        }

        @Test
        @DisplayName("사유가 enum 에 없는 값이면 400 · PRODUCT_100 이고 필드와 허용 값을 알린다")
        void unknownReason() {
            MvcTestResult result = post("{\"claimId\": 1, \"reservationId\": 1, \"reason\": \"EXCHANGE\", " + ITEMS + "}");

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_100");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("reason");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("reason 값이 올바르지 않습니다. 허용 값: CANCELLATION, RETURN");
            verifyNoInteractions(stockInternalUseCase);
        }

        @Test
        @DisplayName("깨진 JSON 은 400 · PRODUCT_100 이고 Response 봉투다")
        void brokenJson() {
            MvcTestResult result = post("{\"claimId\": 1, \"items\": [");

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_100");
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verifyNoInteractions(stockInternalUseCase);
        }

        @Test
        @DisplayName("숫자 자리에 문자면 400 · PRODUCT_100 이고 필드 경로를 짚는다")
        void typeMismatch() {
            MvcTestResult result = post("{\"claimId\": \"a\", \"reservationId\": 1, \"reason\": \"RETURN\", " + ITEMS + "}");

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_100");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("claimId");
            verifyNoInteractions(stockInternalUseCase);
        }
    }

    private MvcTestResult post(String body) {
        return mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static void assertFailure(MvcTestResult result, HttpStatus status, String code, String message) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo(message);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    private static StockRestorationResponse response() {
        return StockRestorationResponse.builder()
            .restorationId("4611686018427388101")
            .claimId(String.valueOf(CLAIM_ID))
            .reservationId(String.valueOf(RESERVATION_ID))
            .reason("CANCELLATION")
            .items(List.of(
                StockRestorationItemInternalItem.builder().skuId("9001").quantity(1).restored(true).build(),
                StockRestorationItemInternalItem.builder().skuId("9004").quantity(2).restored(false).build()
            ))
            .build();
    }
}
