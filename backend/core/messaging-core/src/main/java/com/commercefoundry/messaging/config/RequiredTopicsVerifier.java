package com.commercefoundry.messaging.config;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * 기동 때 이 서비스가 쓰는 토픽이 있는지 본다 (#304 A-2). 앱은 토픽을 만들지 않으므로 없으면 토픽 이름만 남기고 뜨지 않는다 —
 * 첫 발행 · 구독에서야 드러나는 것보다 낫다. 메시징이 켜져 있고 {@code commerce.messaging.required-topics} 가 있을 때만 돈다.
 */
@Slf4j
public class RequiredTopicsVerifier implements SmartInitializingSingleton {

    private final KafkaAdmin kafkaAdmin;
    private final List<String> topics;

    public RequiredTopicsVerifier(KafkaAdmin kafkaAdmin, List<String> topics) {
        this.kafkaAdmin = kafkaAdmin;
        this.topics = List.copyOf(topics);
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (topics.isEmpty()) {
            return;
        }
        try {
            kafkaAdmin.describeTopics(topics.toArray(String[]::new));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Kafka 토픽을 확인하지 못했습니다 — 토픽이 없거나 클러스터에 닿지 않습니다. 토픽은 Infra 스크립트가 만듭니다. topics="
                + topics + ", cause=" + exception.getClass().getSimpleName());
        }
        log.info("kafka topics verified. count={}", topics.size());
    }
}
