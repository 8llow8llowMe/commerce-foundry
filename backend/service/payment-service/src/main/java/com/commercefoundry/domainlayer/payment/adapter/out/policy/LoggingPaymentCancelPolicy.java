package com.commercefoundry.domainlayer.payment.adapter.out.policy;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 취소 정책 훅의 기본 구현 (#308 PR-C) — ERROR 로그와 지표 {@code commerce.payment.order-sync.rejected.total{reason}} 만 남긴다. <b>PG 취소를 하지 않는다</b>.
 * 자동 전액 취소는 #579 가 이 포트의 구현을 바꿔 붙인다. 그 전에는 운영자가 어드민 결제 목록(주문 반영 실패 코드)과 거절 결제 게이지로 찾아 취소한다.
 * 로그에는 아이디 · 사유 코드만 남긴다(결제키 · 금액 · 개인정보 없음).
 */
@Slf4j
@Component
public class LoggingPaymentCancelPolicy implements PaymentCancelPolicy {

    static final String REJECTED_COUNTER = "commerce.payment.order-sync.rejected.total";

    private final ObjectProvider<MeterRegistry> meterRegistry;

    public LoggingPaymentCancelPolicy(ObjectProvider<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void onOrderRejected(long paymentId, long orderId, String rejectionReason) {
        log.error("order rejected approved payment — full cancellation required (automatic cancel not enabled). paymentId={}, orderId={}, reason={}",
            paymentId, orderId, rejectionReason);
        meterRegistry.ifAvailable(registry -> registry.counter(REJECTED_COUNTER, "reason", rejectionReason).increment());
    }
}
