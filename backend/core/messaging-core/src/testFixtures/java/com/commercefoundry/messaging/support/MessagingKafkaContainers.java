package com.commercefoundry.messaging.support;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 서비스 통합 테스트용 Kafka 컨테이너 (#304 A-8) — 운영 클러스터와 같은 apache/kafka(KRaft) 이미지다. {@code @ServiceConnection} 으로 붙이고,
 * 토픽은 테스트가 AdminClient 로 직접 만든다(앱은 만들지 않는다). Docker 없는 로컬은 서비스의 {@code @EnabledWithDockerOrOnCi} 로 건너뛴다.
 */
public final class MessagingKafkaContainers {

    /** Infra 클러스터와 같은 버전. */
    public static final DockerImageName IMAGE = DockerImageName.parse("apache/kafka:4.3.1");

    private MessagingKafkaContainers() {
    }

    public static KafkaContainer kafka() {
        return new KafkaContainer(IMAGE);
    }
}
