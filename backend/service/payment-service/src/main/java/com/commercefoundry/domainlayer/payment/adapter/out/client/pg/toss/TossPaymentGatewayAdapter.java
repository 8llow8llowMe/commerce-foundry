package com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss;

import com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss.dto.TossConfirmRequest;
import com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss.dto.TossCancelRequest;
import com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss.dto.TossAutomaticCancelRequest;
import com.commercefoundry.domainlayer.payment.application.model.PgApprovalRequest;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentGatewayPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgApprovalQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgInquiryQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgCancelResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgCancellationQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgRetentionQueryResult;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.global.properties.TossPaymentsProperties;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 토스페이먼츠 결제 API 어댑터 ({@code payment-guide.md} §1). 토스는 이 패키지({@code adapter/out/client/pg/toss})에만 나온다.
 * 호출은 {@link TossPaymentsResponseSupport} 가 서킷으로 감싸고 결과를 가른다 — 이 클래스는 요청을 만들어 넘기기만 한다.
 */
@Component
@RequiredArgsConstructor
public class TossPaymentGatewayAdapter implements PaymentGatewayPort {

    private final TossPaymentsClient tossPaymentsClient;
    private final TossPaymentsResponseSupport tossPaymentsResponseSupport;
    private final TossPaymentsProperties tossPaymentsProperties;

    @Override
    public PgCancellationQueryResult cancelAutomatic(
        Payment payment, String pgKey, Money amount) {
        if (pgKey == null || !pgKey.matches("[A-Za-z0-9_=-]{6,64}") || amount.currency() != payment.amount().currency()
            || amount.isZero() || amount.isGreaterThan(payment.amount())) {
            return PgCancellationQueryResult.of(PgCancellationQueryResult.State.MANUAL_REVIEW);
        }
        TossAutomaticCancelRequest body = new TossAutomaticCancelRequest("PAYMENT_AUTO_CANCEL:" + pgKey, amount.toMajorUnit(), pgKey, amount.currency().name());
        return tossPaymentsResponseSupport.automaticPost(payment, amount, pgKey,
            () -> tossPaymentsClient.cancelAutomatic(tossPaymentsProperties.basicAuthorization(), pgKey, payment.paymentKey(), body));
    }

    @Override
    public PgCancellationQueryResult inquireCancellation(
        Payment payment, Money amount, String pgKey, String transactionKey) {
        return tossPaymentsResponseSupport.cancellationInquiry(payment, amount, pgKey, transactionKey,
            () -> tossPaymentsClient.getPaymentByPaymentKey(tossPaymentsProperties.basicAuthorization(), payment.paymentKey()));
    }

    /**
     * 카드 · 간편결제처럼 취소가 동기로 잔액에 반영되는 KRW 결제만 대조한다. 가상계좌(환불 계좌 송금) · USD(PayPal 비동기 환불)는
     * 끝나지 않은 환불이 조회에 안 보일 수 있어 부르지 않고 모름이다 ({@code payment-guide.md} §8-1).
     */
    @Override
    public PgRetentionQueryResult inquireRetention(Payment payment, Money expectedBalance) {
        Objects.requireNonNull(payment, "payment");
        if (payment.paymentKey() == null || payment.method() == PaymentMethod.VIRTUAL_ACCOUNT || payment.amount().currency() != CurrencyCode.KRW
            || expectedBalance == null || expectedBalance.currency() != payment.amount().currency()
            || expectedBalance.isGreaterThan(payment.amount())) {
            return PgRetentionQueryResult.of(PgRetentionQueryResult.State.UNKNOWN);
        }
        return tossPaymentsResponseSupport.retentionInquiry(payment, expectedBalance,
            () -> tossPaymentsClient.getPaymentByPaymentKey(tossPaymentsProperties.basicAuthorization(), payment.paymentKey()));
    }

    @Override
    public PgCancelResult cancel(Payment payment, long claimId, Money cancelAmount, Money cumulativeCanceled) {
        Objects.requireNonNull(payment, "payment");
        String key = Long.toString(claimId);
        TossCancelRequest body = new TossCancelRequest("ORDER_CLAIM", cancelAmount.toMajorUnit(), key);
        return tossPaymentsResponseSupport.cancel(payment.pgOrderId(), payment.paymentKey(), payment.amount(), cumulativeCanceled,
            () -> tossPaymentsClient.cancel(tossPaymentsProperties.basicAuthorization(), key, payment.paymentKey(), body));
    }

    /** 금액은 결제 시도의 금액을 통화의 주 단위로 싣는다(KRW {@code 129000}, USD {@code 89.50}). 멱등 키는 결제 시도 아이디라 같은 시도의 재요청이 같은 값을 싣는다. */
    @Override
    public PgApprovalQueryResult approve(PgApprovalRequest request) {
        Objects.requireNonNull(request, "request");
        TossConfirmRequest body = new TossConfirmRequest(request.paymentKey(), request.pgOrderId(), request.amount().toMajorUnit());
        return tossPaymentsResponseSupport.approve(request.pgOrderId(),
            () -> tossPaymentsClient.confirm(tossPaymentsProperties.basicAuthorization(), request.idempotencyKey(), body));
    }

    @Override
    public PgInquiryQueryResult inquire(String pgOrderId) {
        Objects.requireNonNull(pgOrderId, "pgOrderId");
        return tossPaymentsResponseSupport.inquire(pgOrderId, null,
            () -> tossPaymentsClient.getPaymentByOrderId(tossPaymentsProperties.basicAuthorization(), pgOrderId));
    }

    @Override
    public PgInquiryQueryResult inquireByPaymentKey(String paymentKey, String pgOrderId) {
        Objects.requireNonNull(paymentKey, "paymentKey");
        Objects.requireNonNull(pgOrderId, "pgOrderId");
        return tossPaymentsResponseSupport.inquire(pgOrderId, paymentKey,
            () -> tossPaymentsClient.getPaymentByPaymentKey(tossPaymentsProperties.basicAuthorization(), paymentKey));
    }
}
