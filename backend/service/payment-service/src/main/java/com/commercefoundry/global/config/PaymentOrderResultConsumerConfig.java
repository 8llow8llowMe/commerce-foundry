package com.commercefoundry.global.config;

import com.commercefoundry.messaging.config.RequiredTopicsVerifier;
import com.commercefoundry.messaging.event.TopicNames;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * 주문 결제 회신 컨슈머 게이트(#308 PR-C)를 열 때만 회신 토픽과 DLT 를 요구한다 — 결제 승인 outbox 만 운영하는 인스턴스의 기존 기동 계약(공통 required-topics)을 보존한다.
 * order-service {@code OrderPaymentConsumerConfig} 와 같은 모양이다. 토픽은 앱이 만들지 않는다({@code deploy-guide.md} §4-4).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"commerce.messaging.enabled", "payment.order-result.consumer.enabled"}, havingValue = "true")
public class PaymentOrderResultConsumerConfig {

    @Bean
    RequiredTopicsVerifier orderPaymentResultTopicsVerifier(KafkaAdmin kafkaAdmin, TopicNames topicNames) {
        return new RequiredTopicsVerifier(kafkaAdmin, List.of("order.order-paid", "order.order-paid.dlt", "order.order-payment-rejected",
            "order.order-payment-rejected.dlt").stream().map(topicNames::qualified).toList());
    }
}
