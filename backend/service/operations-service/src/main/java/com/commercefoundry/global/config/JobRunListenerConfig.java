package com.commercefoundry.global.config;

import com.commercefoundry.messaging.properties.MessagingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ContainerPostProcessor;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * 작업 실행 신호 리스너({@code JobRunEventListener})의 컨테이너 설정. 워커를 켠 인스턴스에만 둔다.
 *
 * <p>공통 리스너 팩토리는 ack 모드 RECORD 다(messaging-core). 실행 신호는 실행 칸이 없을 때 {@code nack(Duration)} 으로 늦춰 다시 받아야 해서, 이 리스너만
 * MANUAL_IMMEDIATE 로 바꾼다 — 재시도 · DLT 오류 처리와 namespace 검사는 공통 팩토리 그대로다.
 */
@Slf4j
@Configuration
@ConditionalOnBooleanProperty(name = "operations.jobs.worker.enabled")
public class JobRunListenerConfig {

    public JobRunListenerConfig(MessagingProperties messagingProperties) {
        if (!messagingProperties.enabled()) {
            // 등록 · 조회는 되지만 실행 신호를 받지 못해 작업이 QUEUED 로 남는다 — outbox 에 쌓인 신호는 켜면 나간다
            log.warn("job worker enabled but kafka messaging disabled — jobs stay QUEUED until {}=true", MessagingProperties.ENABLED);
        }
    }

    @Bean
    public ContainerPostProcessor<String, String, AbstractMessageListenerContainer<String, String>> jobRunContainerPostProcessor() {
        return container -> container.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    }
}
