package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentGatewayPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgInquiryQueryResult;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.global.properties.PaymentWebhookProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PG 조회 전후의 건별 DB 트랜잭션을 조합한다. 포트와 QueryResult는 Processor 내부에서만 사용한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentWebhookScanProcessor {
    private final PaymentWebhookProperties properties;
    private final PaymentWebhookProcessor processor;
    private final PaymentGatewayPort gateway;

    @Transactional(propagation = Propagation.NEVER)
    public void process() {
        if (!properties.enabled()) {
            return;
        }
        for (long id : processor.scan()) {
            try {
                var work = processor.claim(id);
                if (work.isEmpty()) {
                    continue;
                }
                var payment = work.get().payment();
                PgInquiryQueryResult result;
                try {
                    result = gateway.inquireByPaymentKey(payment.paymentKey(), payment.pgOrderId());
                } catch (RuntimeException unavailable) {
                    result = new PgInquiryQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR);
                }
                processor.reconcile(work.get(), result);
            } catch (RuntimeException failed) {
                // 원문·digest·PG 오류·스택은 출력하지 않는다. 실패 작업은 lease 만료 뒤 다시 확보한다.
                log.warn("payment webhook work deferred. inboxId={}, causeType={}", id, failed.getClass().getSimpleName());
            }
        }
    }
}
