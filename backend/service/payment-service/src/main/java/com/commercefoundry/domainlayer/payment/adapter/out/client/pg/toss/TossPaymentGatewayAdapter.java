package com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss;

import com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss.dto.TossConfirmRequest;
import com.commercefoundry.domainlayer.payment.application.model.PgApprovalRequest;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentGatewayPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgApprovalQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgInquiryQueryResult;
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
        return tossPaymentsResponseSupport.inquire(pgOrderId,
            () -> tossPaymentsClient.getPaymentByOrderId(tossPaymentsProperties.basicAuthorization(), pgOrderId));
    }
}
