package com.commercefoundry.domainlayer.payment.adapter.out.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** 기본 취소 정책 훅은 지표만 올린다 — PG 를 부르지 않는다(자동 전액 취소는 #579). */
class LoggingPaymentCancelPolicyTest {

    @Test
    void countsRejectionByReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("meterRegistry", registry);
        LoggingPaymentCancelPolicy policy = new LoggingPaymentCancelPolicy(beans.getBeanProvider(MeterRegistry.class));

        policy.onOrderRejected(1L, 2L, "ORDER_CANCELED");
        policy.onOrderRejected(3L, 4L, "ORDER_CANCELED");
        policy.onOrderRejected(5L, 6L, "STOCK_UNAVAILABLE");

        assertThat(registry.get(LoggingPaymentCancelPolicy.REJECTED_COUNTER).tag("reason", "ORDER_CANCELED").counter().count()).isEqualTo(2.0);
        assertThat(registry.get(LoggingPaymentCancelPolicy.REJECTED_COUNTER).tag("reason", "STOCK_UNAVAILABLE").counter().count()).isEqualTo(1.0);
    }

    @Test
    void worksWithoutMeterRegistry() {
        LoggingPaymentCancelPolicy policy = new LoggingPaymentCancelPolicy(new DefaultListableBeanFactory().getBeanProvider(MeterRegistry.class));

        assertThatCode(() -> policy.onOrderRejected(1L, 2L, "ORDER_NOT_FOUND")).doesNotThrowAnyException();
    }
}
