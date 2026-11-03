package com.commercefoundry.domainlayer.payment.adapter.out.metrics;

import com.commercefoundry.domainlayer.payment.application.port.out.OrderPaymentReplyMetricsPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** {@link OrderPaymentReplyMetricsPort} 의 Micrometer 구현 — {@code commerce.payment.order-sync.source-mismatch.total}(태그 없음). 레지스트리가 없으면 아무것도 하지 않는다. */
@Component
public class OrderPaymentReplyMetricsAdapter implements OrderPaymentReplyMetricsPort {

    public static final String SOURCE_MISMATCH = "commerce.payment.order-sync.source-mismatch.total";

    private final ObjectProvider<MeterRegistry> meterRegistry;

    public OrderPaymentReplyMetricsAdapter(ObjectProvider<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordSourceMismatch() {
        meterRegistry.ifAvailable(registry -> registry.counter(SOURCE_MISMATCH).increment());
    }
}
