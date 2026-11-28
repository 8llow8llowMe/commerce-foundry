package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentCancelPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelVoidQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelVoidQueryResult.Outcome;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 후 취소 거절 전 payment 무환불 판정 (#17 A4) — outcome 네 가지 · 포트 예외의 오류 코드 매핑, 본문이 클레임에 저장된 값인지, 트랜잭션 밖에서만 도는지,
 * 판정을 부르는 곳이 이 Processor 하나인지는 {@code OrderServiceArchitectureTest} 가 본다. 아이디 · 금액은 모두 가짜다.
 */
class ClaimCancellationVoidProcessorTest {
    private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");
    private static final long ORDER_ID = 1L;
    private static final long CLAIM_ID = 50L;
    private static final long PAYMENT_ID = 77L;
    private static final Money REFUND = krw(30_000);

    private final PaymentCancelPort port = mock(PaymentCancelPort.class);
    private final ClaimCancellationVoidProcessor processor = new ClaimCancellationVoidProcessor(port);

    private static Claim rejectedRefund() {
        return new Claim(CLAIM_ID, ORDER_ID, Claim.cancellationRequestKey(ORDER_ID), Claim.Type.CANCELLATION, Claim.Status.REFUND_FAILED, REFUND,
            PAYMENT_ID, 1, null, "PAYMENT_019", List.of(new ClaimItem(CLAIM_ID + 1, 101L, 1, ClaimItem.Reason.ORDER_MISTAKE)), NOW, 4L);
    }

    @Test
    @DisplayName("무환불 확정(NO_REFUND_CONFIRMED)이면 그대로 돌아간다 — 본문은 클레임에 저장된 결제 · 주문 · 클레임 아이디 · 환불액이다")
    void confirmedPassesWithStoredValues() {
        when(port.confirmNoRefund(PAYMENT_ID, ORDER_ID, CLAIM_ID, REFUND)).thenReturn(new PaymentCancelVoidQueryResult(Outcome.NO_REFUND_CONFIRMED,
            "CLAIM_REJECTED_BY_PG"));

        assertThatCode(() -> processor.confirmNoRefund(rejectedRefund())).doesNotThrowAnyException();

        verify(port).confirmNoRefund(PAYMENT_ID, ORDER_ID, CLAIM_ID, REFUND);
        verify(port, never()).cancel(anyLong(), anyLong(), any());
    }

    @ParameterizedTest(name = "{0} → {2}")
    @CsvSource({
        "REFUNDED, PG_CANCEL_SUCCEEDED, CANCELLATION_REJECTION_REFUND_FOUND",
        "UNCONFIRMED, PAYMENT_019, CANCELLATION_REJECTION_UNVERIFIED",
        "UNCONFIRMED, PENDING_CANCEL_EXISTS, CANCELLATION_REJECTION_UNVERIFIED",
        "UNAVAILABLE, CIRCUIT_OPEN, CANCELLATION_REJECTION_CHECK_UNAVAILABLE",
        "UNAVAILABLE, PG_UNAVAILABLE, CANCELLATION_REJECTION_CHECK_UNAVAILABLE"
    })
    @DisplayName("확정이 아니면 거절을 막는다 — REFUNDED 409 ORDER_052 · UNCONFIRMED 409 ORDER_053 · UNAVAILABLE 503 ORDER_054. 분기는 outcome 만 보고 사유 코드는 보지 않는다")
    void notConfirmedOutcomesMapToCodes(Outcome outcome, String reasonCode, OrderErrorCode expected) {
        when(port.confirmNoRefund(PAYMENT_ID, ORDER_ID, CLAIM_ID, REFUND)).thenReturn(new PaymentCancelVoidQueryResult(outcome, reasonCode));

        assertThatThrownBy(() -> processor.confirmNoRefund(rejectedRefund())).isInstanceOfSatisfying(OrderException.class,
            failure -> assertThat(failure.getErrorCode()).isEqualTo(expected));
    }

    @Test
    @DisplayName("오류 코드의 HTTP 상태 — 052 · 053 은 409, 054 는 503 이다")
    void codesCarryHttpStatus() {
        assertThat(OrderErrorCode.CANCELLATION_REJECTION_REFUND_FOUND.getCode()).isEqualTo("ORDER_052");
        assertThat(OrderErrorCode.CANCELLATION_REJECTION_REFUND_FOUND.getHttpStatus().value()).isEqualTo(409);
        assertThat(OrderErrorCode.CANCELLATION_REJECTION_UNVERIFIED.getCode()).isEqualTo("ORDER_053");
        assertThat(OrderErrorCode.CANCELLATION_REJECTION_UNVERIFIED.getHttpStatus().value()).isEqualTo(409);
        assertThat(OrderErrorCode.CANCELLATION_REJECTION_CHECK_UNAVAILABLE.getCode()).isEqualTo("ORDER_054");
        assertThat(OrderErrorCode.CANCELLATION_REJECTION_CHECK_UNAVAILABLE.getHttpStatus().value()).isEqualTo(503);
        assertThat(OrderErrorCode.CANCELLATION_ALREADY_REJECTED.getCode()).isEqualTo("ORDER_055");
        assertThat(OrderErrorCode.CANCELLATION_ALREADY_REJECTED.getHttpStatus().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("포트가 Feign 밖 예외(서비스 토큰 발급 실패 등)를 던지거나 결과가 없으면 판정을 받지 못한 것이다 — 503 ORDER_054, 원인은 싣지 않는다")
    void portFailureIsUnavailable() {
        when(port.confirmNoRefund(PAYMENT_ID, ORDER_ID, CLAIM_ID, REFUND)).thenThrow(new IllegalStateException("token issuer down"));

        assertThatThrownBy(() -> processor.confirmNoRefund(rejectedRefund())).isInstanceOfSatisfying(OrderException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(OrderErrorCode.CANCELLATION_REJECTION_CHECK_UNAVAILABLE);
            assertThat(failure.getCause()).isNull();
        });

        doReturn(null).when(port).confirmNoRefund(PAYMENT_ID, ORDER_ID, CLAIM_ID, REFUND);
        assertThatThrownBy(() -> processor.confirmNoRefund(rejectedRefund())).isInstanceOfSatisfying(OrderException.class,
            failure -> assertThat(failure.getErrorCode()).isEqualTo(OrderErrorCode.CANCELLATION_REJECTION_CHECK_UNAVAILABLE));
    }

    @Test
    @DisplayName("트랜잭션 밖에서만 부른다 — PG 잔액 조회를 기다리는 동안 DB 커넥션 · 주문 잠금을 쥐지 않는다(Propagation.NEVER)")
    void runsOutsideTransactions() throws NoSuchMethodException {
        Transactional transactional = ClaimCancellationVoidProcessor.class.getMethod("confirmNoRefund", Claim.class).getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.NEVER);
    }
}
