package com.commercefoundry.domainlayer.payment.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.item.PaymentFailureItem;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.response.PaymentPrepareResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.response.PaymentResponse;
import com.commercefoundry.domainlayer.payment.application.command.PaymentConfirmCommand;
import com.commercefoundry.domainlayer.payment.application.command.PaymentPrepareCommand;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentRetryLaterException;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentWebUseCase;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentTransitionException;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 고객 결제 API 의 HTTP 계약 — 인증(익명 401 · ADMIN 403), 201 / 200, 아이디 문자열, 필드 검증 코드, Idempotency-Key 누락 · 형식, 198,
 * 업무 오류 상태 코드 · Retry-After. 테스트 값은 모두 가짜다.
 */
@WebMvcTest(PaymentWebController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class PaymentWebControllerTest {

    private static final String KEY = "3f2b8c1e-6a4d-4e2f-9b1a-7c5d2e8f0a11";
    private static final String PAYMENT_ID = "4611686018427387906";
    private static final String PAYMENT_KEY = "tgen_20261021000000abcdEFGH";
    private static final String CONFIRM_BODY = "{\"paymentKey\":\"" + PAYMENT_KEY + "\",\"pgOrderId\":\"" + PAYMENT_ID + "\","
        + "\"amount\":{\"amount\":129000,\"currency\":\"KRW\"}}";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private PaymentWebUseCase paymentWebUseCase;

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    private MvcTestResult prepare(String body) {
        return mvc.post().uri("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))
            .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult confirm(String key, String body) {
        MockMvcRequestBuilder request = mvc.post().uri("/api/v1/payments/confirm").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))
            .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header(PaymentWebController.IDEMPOTENCY_KEY_HEADER, key);
        }
        return request.exchange();
    }

    private MvcTestResult get(String paymentId) {
        return mvc.get().uri("/api/v1/payments/" + paymentId).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange();
    }

    static void assertFailure(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    private static void assertFieldError(MvcTestResult result, String code, String field) {
        assertFailure(result, HttpStatus.BAD_REQUEST, code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
    }

    private static PaymentResponse done() {
        return PaymentResponse.builder().paymentId(PAYMENT_ID).orderId("4611686018427387905").pgOrderId(PAYMENT_ID)
            .status(PaymentStatus.DONE.toMetadata()).amount(MoneyItem.of(129_000, "KRW")).orderName("주문")
            .method(CodeNameDescriptionMetadata.of("CARD", "카드", "카드")).approvedAt(Instant.parse("2026-10-21T01:00:03Z")).build();
    }

    private static PaymentPrepareResponse ready() {
        return PaymentPrepareResponse.builder().paymentId(PAYMENT_ID).orderId("4611686018427387905").pgOrderId(PAYMENT_ID)
            .status(PaymentStatus.READY.toMetadata()).amount(MoneyItem.of(129_000, "KRW")).orderName("주문")
            .paymentDueAt(Instant.parse("2026-10-21T01:30:00Z")).build();
    }

    @Nested
    @DisplayName("인증 · 인가")
    class Authorization {

        @Test
        @DisplayName("토큰 없이 부르면 세 API 모두 401 · SECURITY_001 이고 유스케이스에 닿지 않는다")
        void anonymousIsRejected() {
            assertFailure(mvc.post().uri("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":1}").exchange(),
                HttpStatus.UNAUTHORIZED, "SECURITY_001");
            assertFailure(mvc.post().uri("/api/v1/payments/confirm").header(PaymentWebController.IDEMPOTENCY_KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content(CONFIRM_BODY).exchange(), HttpStatus.UNAUTHORIZED, "SECURITY_001");
            assertFailure(mvc.get().uri("/api/v1/payments/" + PAYMENT_ID).exchange(), HttpStatus.UNAUTHORIZED, "SECURITY_001");
            verifyNoInteractions(paymentWebUseCase);
        }

        @Test
        @DisplayName("ADMIN 은 403 · SECURITY_005 — 고객 전용이다")
        void adminIsRejected() {
            assertFailure(mvc.get().uri("/api/v1/payments/" + PAYMENT_ID).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange(),
                HttpStatus.FORBIDDEN, "SECURITY_005");
            assertFailure(mvc.post().uri("/api/v1/payments/confirm").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
                .header(PaymentWebController.IDEMPOTENCY_KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(CONFIRM_BODY).exchange(),
                HttpStatus.FORBIDDEN, "SECURITY_005");
            verifyNoInteractions(paymentWebUseCase);
        }
    }

    @Nested
    @DisplayName("결제 준비")
    class Prepare {

        @Test
        @DisplayName("새 결제 시도는 201 — 회원은 토큰 주체, 주문 아이디는 문자열로 보내도 된다. 응답 아이디는 문자열이다")
        void creates() {
            given(paymentWebUseCase.prepare(anyLong(), any())).willReturn(new PaymentWebUseCase.PrepareResult(true, ready()));

            MvcTestResult result = prepare("{\"orderId\":\"4611686018427387905\"}");

            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(result).bodyJson().extractingPath("$.dataBody.paymentId").isEqualTo(PAYMENT_ID);
            assertThat(result).bodyJson().extractingPath("$.dataBody.pgOrderId").isEqualTo(PAYMENT_ID);
            assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("READY");
            assertThat(result).bodyJson().extractingPath("$.dataBody.amount.amount").isEqualTo(129000);
            assertThat(result).bodyJson().extractingPath("$.dataBody.paymentDueAt").isEqualTo("2026-10-21T01:30:00Z");
            assertThat(result).bodyJson().extractingPath("$.dataBody.failure").isNull();
            verify(paymentWebUseCase).prepare(TestTokens.MEMBER_ID, new PaymentPrepareCommand(4_611_686_018_427_387_905L));
        }

        @Test
        @DisplayName("기존 결제 시도를 돌려주면 200")
        void returnsExisting() {
            given(paymentWebUseCase.prepare(anyLong(), any())).willReturn(new PaymentWebUseCase.PrepareResult(false, ready()));

            assertThat(prepare("{\"orderId\":1}")).hasStatusOk();
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(delimiter = '|', value = {
            "{}|PAYMENT_101|orderId",
            "{\"orderId\":0}|PAYMENT_102|orderId",
            "{\"orderId\":-5}|PAYMENT_102|orderId"
        })
        @DisplayName("주문 아이디 필드 코드")
        void validation(String body, String code, String field) {
            assertFieldError(prepare(body), code, field);
            verifyNoInteractions(paymentWebUseCase);
        }

        @Test
        @DisplayName("깨진 JSON · 숫자가 아닌 아이디는 400 PAYMENT_100")
        void unreadable() {
            assertFailure(prepare("{"), HttpStatus.BAD_REQUEST, "PAYMENT_100");
            assertFailure(prepare("{\"orderId\":\"abc\"}"), HttpStatus.BAD_REQUEST, "PAYMENT_100");
        }

        @Test
        @DisplayName("업무 오류는 코드의 상태로 나간다 — 주문 없음 404 · 기한 임박 409 · 주문 정보 장애 503")
        void businessErrors() {
            willThrow(new PaymentException(PaymentErrorCode.NOT_FOUND_ORDER)).given(paymentWebUseCase).prepare(anyLong(), any());
            assertFailure(prepare("{\"orderId\":1}"), HttpStatus.NOT_FOUND, "PAYMENT_002");

            willThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUE_TOO_CLOSE)).given(paymentWebUseCase).prepare(anyLong(), any());
            assertFailure(prepare("{\"orderId\":1}"), HttpStatus.CONFLICT, "PAYMENT_004");

            willThrow(new PaymentException(PaymentErrorCode.ORDER_SERVICE_UNAVAILABLE)).given(paymentWebUseCase).prepare(anyLong(), any());
            assertFailure(prepare("{\"orderId\":1}"), HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_011");
        }
    }

    @Nested
    @DisplayName("결제 승인")
    class Confirm {

        @Test
        @DisplayName("200 — 키는 UUID, 본문은 명령으로 넘긴다. 응답 아이디는 문자열 · DONE 이면 failure 는 null")
        void confirms() {
            given(paymentWebUseCase.confirm(anyLong(), any(), any())).willReturn(done());

            MvcTestResult result = confirm(KEY, CONFIRM_BODY);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.paymentId").isEqualTo(PAYMENT_ID);
            assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("DONE");
            assertThat(result).bodyJson().extractingPath("$.dataBody.failure").isNull();
            assertThat(result).bodyJson().doesNotHavePath("$.dataBody.paymentKey");
            ArgumentCaptor<PaymentConfirmCommand> command = ArgumentCaptor.forClass(PaymentConfirmCommand.class);
            verify(paymentWebUseCase).confirm(eq(TestTokens.MEMBER_ID), eq(UUID.fromString(KEY)), command.capture());
            assertThat(command.getValue()).isEqualTo(new PaymentConfirmCommand(PAYMENT_KEY, PAYMENT_ID, 129_000, "KRW"));
        }

        @Test
        @DisplayName("FAILED 면 failure 에 코드 · 문구를 싣는다")
        void failedCarriesFailure() {
            PaymentResponse failed = PaymentResponse.builder().paymentId(PAYMENT_ID).orderId("1").pgOrderId(PAYMENT_ID).status(PaymentStatus.FAILED.toMetadata())
                .amount(MoneyItem.of(129_000, "KRW")).orderName("주문").failure(new PaymentFailureItem("REJECT_CARD_COMPANY", "카드사 거절")).build();
            given(paymentWebUseCase.confirm(anyLong(), any(), any())).willReturn(failed);

            MvcTestResult result = confirm(KEY, CONFIRM_BODY);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.failure.code").isEqualTo("REJECT_CARD_COMPANY");
        }

        @Test
        @DisplayName("Idempotency-Key 가 없으면 400 PAYMENT_113, UUID 가 아니면 400 PAYMENT_114 — field 는 헤더 이름")
        void idempotencyKey() {
            assertFieldError(confirm(null, CONFIRM_BODY), "PAYMENT_113", PaymentWebController.IDEMPOTENCY_KEY_HEADER);
            assertFieldError(confirm("not-a-uuid", CONFIRM_BODY), "PAYMENT_114", PaymentWebController.IDEMPOTENCY_KEY_HEADER);
            verifyNoInteractions(paymentWebUseCase);
        }

        @ParameterizedTest(name = "{1} {2}")
        @CsvSource(delimiter = '|', value = {
            "{\"pgOrderId\":\"4611686018427387906\",\"amount\":{\"amount\":1,\"currency\":\"KRW\"}}|PAYMENT_103|paymentKey",
            "{\"paymentKey\":\"LONG\",\"pgOrderId\":\"4611686018427387906\",\"amount\":{\"amount\":1,\"currency\":\"KRW\"}}|PAYMENT_104|paymentKey",
            "{\"paymentKey\":\"k\",\"amount\":{\"amount\":1,\"currency\":\"KRW\"}}|PAYMENT_105|pgOrderId",
            "{\"paymentKey\":\"k\",\"pgOrderId\":\"12345\",\"amount\":{\"amount\":1,\"currency\":\"KRW\"}}|PAYMENT_106|pgOrderId",
            "{\"paymentKey\":\"k\",\"pgOrderId\":\"123456.7\",\"amount\":{\"amount\":1,\"currency\":\"KRW\"}}|PAYMENT_107|pgOrderId",
            "{\"paymentKey\":\"k\",\"pgOrderId\":\"4611686018427387906\"}|PAYMENT_108|amount",
            "{\"paymentKey\":\"k\",\"pgOrderId\":\"4611686018427387906\",\"amount\":{\"currency\":\"KRW\"}}|PAYMENT_109|amount.amount",
            "{\"paymentKey\":\"k\",\"pgOrderId\":\"4611686018427387906\",\"amount\":{\"amount\":0,\"currency\":\"KRW\"}}|PAYMENT_110|amount.amount",
            "{\"paymentKey\":\"k\",\"pgOrderId\":\"4611686018427387906\",\"amount\":{\"amount\":1}}|PAYMENT_111|amount.currency",
            "{\"paymentKey\":\"k\",\"pgOrderId\":\"4611686018427387906\",\"amount\":{\"amount\":1,\"currency\":\"krw\"}}|PAYMENT_112|amount.currency"
        })
        @DisplayName("본문 필드 코드 — 필드 선언 순서 · 필수 → 길이 → 형식")
        void bodyValidation(String body, String code, String field) {
            assertFieldError(confirm(KEY, body.replace("LONG", "k".repeat(201))), code, field);
            verifyNoInteractions(paymentWebUseCase);
        }

        @Test
        @DisplayName("업무 오류 — 금액 불일치 400 PAYMENT_008 · 처리 중 409 PAYMENT_007 · 키 재사용 409 PAYMENT_014 · 기록 실패 500 PAYMENT_013")
        void businessErrors() {
            willThrow(new PaymentException(PaymentErrorCode.AMOUNT_MISMATCH)).given(paymentWebUseCase).confirm(anyLong(), any(), any());
            assertFailure(confirm(KEY, CONFIRM_BODY), HttpStatus.BAD_REQUEST, "PAYMENT_008");

            willThrow(new PaymentException(PaymentErrorCode.APPROVAL_IN_PROGRESS)).given(paymentWebUseCase).confirm(anyLong(), any(), any());
            assertFailure(confirm(KEY, CONFIRM_BODY), HttpStatus.CONFLICT, "PAYMENT_007");

            willThrow(new PaymentException(PaymentErrorCode.IDEMPOTENCY_KEY_REUSED)).given(paymentWebUseCase).confirm(anyLong(), any(), any());
            assertFailure(confirm(KEY, CONFIRM_BODY), HttpStatus.CONFLICT, "PAYMENT_014");

            willThrow(new PaymentException(PaymentErrorCode.APPROVAL_RECORD_FAILED)).given(paymentWebUseCase).confirm(anyLong(), any(), any());
            assertFailure(confirm(KEY, CONFIRM_BODY), HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT_013");
        }

        @Test
        @DisplayName("결제사에 보내지 못했으면 503 PAYMENT_010 + Retry-After(초, 올림 · 최소 1)")
        void retryLater() {
            willThrow(new PaymentRetryLaterException(PaymentErrorCode.GATEWAY_UNAVAILABLE, Duration.ofSeconds(5))).given(paymentWebUseCase).confirm(anyLong(), any(), any());
            MvcTestResult result = confirm(KEY, CONFIRM_BODY);
            assertFailure(result, HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_010");
            assertThat(result).headers().hasValue(HttpHeaders.RETRY_AFTER, "5");

            willThrow(new PaymentRetryLaterException(PaymentErrorCode.GATEWAY_UNAVAILABLE, Duration.ofMillis(1))).given(paymentWebUseCase).confirm(anyLong(), any(), any());
            assertThat(confirm(KEY, CONFIRM_BODY)).headers().hasValue(HttpHeaders.RETRY_AFTER, "1");
        }

        @Test
        @DisplayName("번역되지 않은 도메인 예외는 마지막 안전망이 409 PAYMENT_012 로 받는다 — 예외 메시지(상태)를 싣지 않는다")
        void untranslatedDomainException() {
            willThrow(new PaymentTransitionException(PaymentStatus.DONE, PaymentTransitionException.Action.APPROVE)).given(paymentWebUseCase).confirm(anyLong(), any(), any());

            MvcTestResult result = confirm(KEY, CONFIRM_BODY);

            assertFailure(result, HttpStatus.CONFLICT, "PAYMENT_012");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").asString().doesNotContain("status=").doesNotContain("APPROVE");
        }
    }

    @Nested
    @DisplayName("상태 조회")
    class Get {

        @Test
        @DisplayName("200 — 경로의 아이디와 토큰 주체로 조회한다")
        void gets() {
            given(paymentWebUseCase.getPayment(TestTokens.MEMBER_ID, 4_611_686_018_427_387_906L)).willReturn(done());

            MvcTestResult result = get(PAYMENT_ID);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.paymentId").isEqualTo(PAYMENT_ID);
            assertThat(result).bodyJson().extractingPath("$.dataBody.method.code").isEqualTo("CARD");
        }

        @Test
        @DisplayName("숫자가 아닌 아이디는 400 PAYMENT_198, 없거나 남의 결제는 404 PAYMENT_001")
        void errors() {
            assertFailure(get("abc"), HttpStatus.BAD_REQUEST, "PAYMENT_198");

            given(paymentWebUseCase.getPayment(anyLong(), anyLong())).willThrow(new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT));
            assertFailure(get(PAYMENT_ID), HttpStatus.NOT_FOUND, "PAYMENT_001");
        }
    }
}
