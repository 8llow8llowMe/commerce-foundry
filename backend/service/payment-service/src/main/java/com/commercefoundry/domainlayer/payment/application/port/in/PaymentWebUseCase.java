package com.commercefoundry.domainlayer.payment.application.port.in;

import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.response.PaymentPrepareResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.response.PaymentResponse;
import com.commercefoundry.domainlayer.payment.application.command.PaymentConfirmCommand;
import com.commercefoundry.domainlayer.payment.application.command.PaymentPrepareCommand;
import java.util.UUID;

/**
 * 고객 결제 API — 결제 준비 · 승인 · 상태 조회. 회원은 언제나 JWT 주체다 ({@code api-design-guide.md} §5).
 */
public interface PaymentWebUseCase {

    /**
     * 주문의 결제 시도를 준비한다. 활성 결제 시도가 이미 있으면 그대로 돌려준다.
     *
     * @return {@code created} — 새 결제 시도를 만들었으면 true(201), 기존 시도를 돌려주면 false(200)
     */
    PrepareResult prepare(long memberId, PaymentPrepareCommand command);

    /**
     * 결제를 승인한다. 같은 {@code idempotencyKey} 의 재요청에는 처음 결과(현재 상태)를 저장 없이 돌려준다 ({@code api-design-guide.md} §6).
     *
     * @return DONE · FAILED · UNKNOWN 결제
     */
    PaymentResponse confirm(long memberId, UUID idempotencyKey, PaymentConfirmCommand command);

    PaymentResponse getPayment(long memberId, long paymentId);

    /** 결제 준비 결과 — 새로 만들었는지와 응답. */
    record PrepareResult(boolean created, PaymentPrepareResponse payment) {
    }
}
