package com.commercefoundry.global.config;

import com.commercefoundry.messaging.config.RequiredTopicsVerifier;
import com.commercefoundry.messaging.event.TopicNames;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/** 보호 WIP 4dae63ad의 토픽 검증 hunk 인수. 전용 소비 게이트가 열릴 때만 기존 네 토픽을 요구하며 앱이 생성하지 않는다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"commerce.messaging.enabled", "payment.order-result.consumer.enabled"}, havingValue = "true")
public class PaymentOrderResultConsumerConfig {
    @Bean
    RequiredTopicsVerifier orderPaymentResultTopicsVerifier(KafkaAdmin kafkaAdmin, TopicNames topicNames) {
        return new RequiredTopicsVerifier(kafkaAdmin, List.of("order.order-paid", "order.order-paid.dlt", "order.order-payment-rejected",
            "order.order-payment-rejected.dlt").stream().map(topicNames::qualified).toList());
    }
}
