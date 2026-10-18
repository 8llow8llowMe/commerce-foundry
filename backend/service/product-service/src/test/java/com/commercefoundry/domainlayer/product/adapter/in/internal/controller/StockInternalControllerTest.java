package com.commercefoundry.domainlayer.product.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.StockReservationItemInternalItem;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockReservationResponse;
import com.commercefoundry.domainlayer.product.application.command.StockReservationCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.StockInternalUseCase;
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
import org.junit.jupiter.params.provider.ValueSource;
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
 * 서비스 간 재고 선점 · 확정 · 해제의 HTTP 계약 — 권한, 본문 · 경로 검증 코드, 응답 식별자 String, 도메인 오류 봉투.
 *
 * <p>구성은 {@link SkuInternalControllerTest} 와 같다 — internal 체인 · 사용자 체인을 함께 올리고 인증 주체를 {@code @WithMockUser} 로 넣어
 * 핸들러의 허용 호출자({@code @PreAuthorize})를 본다. 서비스 토큰 검증 · 익명 401 은 security-core 테스트와 서비스 인가 스모크가 본다.
 */
@WebMvcTest(StockInternalController.class)
@Import({
    InternalResourceServerConfigurer.class,
    InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class StockInternalControllerTest {

    private static final String PATH = "/internal/v1/stock-reservations";
    private static final String ORDER_SERVICE = "SERVICE_order-service";
    private static final long ORDER_ID = 4611686018427387904L;
    private static final long RESERVATION_ID = 4611686018427388001L;
    private static final String VALID_BODY = """
        {"orderId": 4611686018427387904, "items": [{"skuId": 9004, "quantity": 2}, {"skuId": 9001, "quantity": 1}]}
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
        @DisplayName("선점: order-service 권한이면 200 이고 Response 봉투다 — 식별자는 String, 만료 시각은 ISO-8601 UTC")
        void reserveIsAllowed() {
            StockReservationCommand command = new StockReservationCommand(ORDER_ID, List.of(
                new StockReservationCommand.Item(9004L, 2), new StockReservationCommand.Item(9001L, 1)
            ));
            given(stockInternalUseCase.reserve(command)).willReturn(response("RESERVED"));

            MvcTestResult result = post(PATH, VALID_BODY);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.reservationId").isEqualTo("4611686018427388001");
            assertThat(result).bodyJson().extractingPath("$.dataBody.orderId").isEqualTo("4611686018427387904");
            assertThat(result).bodyJson().extractingPath("$.dataBody.status").isEqualTo("RESERVED");
            assertThat(result).bodyJson().extractingPath("$.dataBody.expiresAt").isEqualTo("2026-10-18T09:30:00Z");
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].skuId").isEqualTo("9001");
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[0].quantity").isEqualTo(1);
            assertThat(result).bodyJson().extractingPath("$.dataBody.items[1].deducted").isEqualTo(false);
            verify(stockInternalUseCase).reserve(command);
        }

        @Test
        @WithMockUser(authorities = ORDER_SERVICE)
        @DisplayName("확정: 경로의 선점 아이디로 부르고 200 이다")
        void confirmIsAllowed() {
            given(stockInternalUseCase.confirm(RESERVATION_ID)).willReturn(response("CONFIRMED"));

            MvcTestResult result = post(PATH + "/" + RESERVATION_ID + "/confirmation", null);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.status").isEqualTo("CONFIRMED");
        }

        @Test
        @WithMockUser(authorities = ORDER_SERVICE)
        @DisplayName("해제: 경로의 선점 아이디로 부르고 200 이다")
        void releaseIsAllowed() {
            given(stockInternalUseCase.release(RESERVATION_ID)).willReturn(response("RELEASED"));

            MvcTestResult result = post(PATH + "/" + RESERVATION_ID + "/release", null);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.status").isEqualTo("RELEASED");
        }

        @ParameterizedTest
        @ValueSource(strings = {PATH, PATH + "/1/confirmation", PATH + "/1/release"})
        @WithMockUser(authorities = "SERVICE_payment-service")
        @DisplayName("다른 서비스 권한이면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void otherServiceIsForbidden(String path) {
            SecurityEnvelopeAssertions.assertEnvelope(post(path, VALID_BODY).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(stockInternalUseCase);
        }

        @ParameterizedTest
        @ValueSource(strings = {PATH, PATH + "/1/confirmation", PATH + "/1/release"})
        @WithMockUser(roles = "CUSTOMER")
        @DisplayName("회원 권한(ROLE_CUSTOMER)이면 403 · SECURITY_005 다")
        void memberIsForbidden(String path) {
            SecurityEnvelopeAssertions.assertEnvelope(post(path, VALID_BODY).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(stockInternalUseCase);
        }
    }

    @Nested
    @DisplayName("도메인 오류 봉투")
    @WithMockUser(authorities = ORDER_SERVICE)
    class DomainErrors {

        @Test
        @DisplayName("재고 부족은 409 · PRODUCT_021 이고 메시지에 부족한 SKU 아이디가 실린다")
        void insufficient() {
            given(stockInternalUseCase.reserve(any()))
                .willThrow(new ProductException(ProductErrorCode.STOCK_INSUFFICIENT, List.of(9001L, 9004L)));

            assertFailure(post(PATH, VALID_BODY), HttpStatus.CONFLICT, "PRODUCT_021", "재고가 부족한 SKU 가 있습니다: [9001, 9004]");
        }

        @Test
        @DisplayName("없는 선점의 확정은 404 · PRODUCT_003")
        void notFound() {
            given(stockInternalUseCase.confirm(RESERVATION_ID)).willThrow(new ProductException(ProductErrorCode.NOT_FOUND_STOCK_RESERVATION));

            assertFailure(post(PATH + "/" + RESERVATION_ID + "/confirmation", null), HttpStatus.NOT_FOUND, "PRODUCT_003", "존재하지 않는 재고 선점입니다.");
        }

        @Test
        @DisplayName("확정된 선점의 해제는 409 · PRODUCT_024")
        void releaseConfirmed() {
            given(stockInternalUseCase.release(RESERVATION_ID)).willThrow(new ProductException(ProductErrorCode.STOCK_RESERVATION_CONFIRMED));

            assertFailure(post(PATH + "/" + RESERVATION_ID + "/release", null), HttpStatus.CONFLICT, "PRODUCT_024", "이미 확정된 재고 선점입니다.");
        }
    }

    @Nested
    @DisplayName("본문 검증")
    @WithMockUser(authorities = ORDER_SERVICE)
    class BodyValidation {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', textBlock = """
            orderId 없음         | {"items": [{"skuId": 1, "quantity": 1}]}                    | PRODUCT_112 | orderId          | orderId 는 필수입니다.
            orderId 0           | {"orderId": 0, "items": [{"skuId": 1, "quantity": 1}]}      | PRODUCT_113 | orderId          | orderId 는 양수여야 합니다.
            orderId 음수          | {"orderId": -1, "items": [{"skuId": 1, "quantity": 1}]}     | PRODUCT_113 | orderId          | orderId 는 양수여야 합니다.
            items 없음           | {"orderId": 1}                                              | PRODUCT_114 | items            | items 는 필수입니다.
            items 빈 배열         | {"orderId": 1, "items": []}                                 | PRODUCT_114 | items            | items 는 필수입니다.
            items 원소 null      | {"orderId": 1, "items": [null]}                             | PRODUCT_116 | items[0]         | items 의 원소는 비어 있을 수 없습니다.
            skuId 없음           | {"orderId": 1, "items": [{"quantity": 1}]}                  | PRODUCT_117 | items[0].skuId   | skuId 는 필수입니다.
            skuId 0             | {"orderId": 1, "items": [{"skuId": 0, "quantity": 1}]}      | PRODUCT_118 | items[0].skuId   | skuId 는 양수여야 합니다.
            quantity 없음        | {"orderId": 1, "items": [{"skuId": 1}]}                     | PRODUCT_119 | items[0].quantity | quantity 는 필수입니다.
            quantity 0          | {"orderId": 1, "items": [{"skuId": 1, "quantity": 0}]}      | PRODUCT_120 | items[0].quantity | quantity 는 1 이상이어야 합니다.
            quantity 음수         | {"orderId": 1, "items": [{"skuId": 1, "quantity": -2}]}     | PRODUCT_120 | items[0].quantity | quantity 는 1 이상이어야 합니다.
            """)
        @DisplayName("필드별 검증 코드")
        void fieldErrors(String name, String body, String code, String field, String message) {
            MvcTestResult result = post(PATH, body);

            assertFailure(result, HttpStatus.BAD_REQUEST, code, message);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
            verifyNoInteractions(stockInternalUseCase);
        }

        @Test
        @DisplayName("항목 101개(중복 포함 원문 개수)면 400 · PRODUCT_115")
        void tooManyItems() {
            String items = IntStream.rangeClosed(1, 101).mapToObj(i -> "{\"skuId\": 1, \"quantity\": 1}").collect(Collectors.joining(","));

            assertFailure(post(PATH, "{\"orderId\": 1, \"items\": [" + items + "]}"), HttpStatus.BAD_REQUEST, "PRODUCT_115", "items 는 최대 100개입니다.");
            verifyNoInteractions(stockInternalUseCase);
        }

        @Test
        @DisplayName("깨진 JSON 은 400 · PRODUCT_100 이고 Response 봉투다")
        void brokenJson() {
            MvcTestResult result = post(PATH, "{\"orderId\": 1, \"items\": [");

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_100");
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verifyNoInteractions(stockInternalUseCase);
        }

        @Test
        @DisplayName("숫자 자리에 문자면 400 · PRODUCT_100 이고 필드 경로를 짚는다")
        void typeMismatch() {
            MvcTestResult result = post(PATH, "{\"orderId\": 1, \"items\": [{\"skuId\": 1, \"quantity\": \"a\"}]}");

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_100");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("items[0].quantity");
            verifyNoInteractions(stockInternalUseCase);
        }
    }

    @Nested
    @DisplayName("경로 검증")
    @WithMockUser(authorities = ORDER_SERVICE)
    class PathValidation {

        @ParameterizedTest
        @ValueSource(strings = {"/0/confirmation", "/-1/release"})
        @DisplayName("선점 아이디가 양수가 아니면 400 · PRODUCT_121")
        void notPositive(String suffix) {
            MvcTestResult result = post(PATH + suffix, null);

            assertFailure(result, HttpStatus.BAD_REQUEST, "PRODUCT_121", "reservationId 는 양수여야 합니다.");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("reservationId");
            verifyNoInteractions(stockInternalUseCase);
        }

        @Test
        @DisplayName("숫자가 아니면 400 · PRODUCT_198")
        void notNumber() {
            assertFailure(post(PATH + "/abc/release", null), HttpStatus.BAD_REQUEST, "PRODUCT_198", "reservationId 파라미터 형식이 올바르지 않습니다.");
            verifyNoInteractions(stockInternalUseCase);
        }
    }

    private MvcTestResult post(String path, String body) {
        if (body == null) {
            return mvc.post().uri(path).exchange();
        }
        return mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static void assertFailure(MvcTestResult result, HttpStatus status, String code, String message) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo(message);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    private static StockReservationResponse response(String status) {
        return StockReservationResponse.builder()
            .reservationId(String.valueOf(RESERVATION_ID))
            .orderId(String.valueOf(ORDER_ID))
            .status(status)
            .expiresAt(Instant.parse("2026-10-18T09:30:00Z"))
            .items(List.of(
                StockReservationItemInternalItem.builder().skuId("9001").quantity(1).deducted(true).build(),
                StockReservationItemInternalItem.builder().skuId("9004").quantity(2).deducted(false).build()
            ))
            .build();
    }
}
