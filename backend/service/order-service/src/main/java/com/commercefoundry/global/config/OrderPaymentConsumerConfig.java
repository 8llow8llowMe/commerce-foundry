package com.commercefoundry.global.config;

import com.commercefoundry.messaging.config.RequiredTopicsVerifier;
import com.commercefoundry.messaging.event.TopicNames;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/** 전용 소비 gate를 열 때만 새 토픽을 요구한다. 만료 outbox만 운영하는 인스턴스의 기존 기동 계약을 보존한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"commerce.messaging.enabled", "order.payment-approval.consumer.enabled"}, havingValue = "true")
public class OrderPaymentConsumerConfig {

    @Bean
    RequiredTopicsVerifier paymentApprovalTopicsVerifier(KafkaAdmin kafkaAdmin, TopicNames topicNames) {
        return new RequiredTopicsVerifier(kafkaAdmin, List.of("payment.payment-approved", "payment.payment-approved.dlt", "order.order-paid",
            "order.order-paid.dlt", "order.order-payment-rejected", "order.order-payment-rejected.dlt").stream().map(topicNames::qualified).toList());
    }
}
