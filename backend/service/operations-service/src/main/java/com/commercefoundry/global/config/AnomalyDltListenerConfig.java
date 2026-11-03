package com.commercefoundry.global.config;

import com.commercefoundry.domainlayer.notification.adapter.in.event.AnomalyDltErrorHandler;
import com.commercefoundry.domainlayer.notification.domain.model.AnomalySource;
import com.commercefoundry.global.properties.AnomalyNotificationProperties;
import com.commercefoundry.messaging.config.RequiredTopicsVerifier;
import com.commercefoundry.messaging.properties.MessagingProperties;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.RecordInterceptor;

@Configuration
@ConditionalOnBooleanProperty("operations.notifications.anomaly.enabled")
public class AnomalyDltListenerConfig {
    public AnomalyDltListenerConfig(MessagingProperties messaging, FeignClientProperties feign, Environment environment) {
        if (!messaging.enabled()) {
            throw new IllegalStateException("Anomaly notifications require enabled Kafka messaging");
        }
        if (messaging.namespace() == null || topics(messaging).stream().anyMatch(topic -> topic.length() > 249)
            || (messaging.namespace() + ".operations.anomaly-dlt").length() > 255) {
            throw new IllegalStateException("Anomaly Kafka topic or group name exceeds supported length");
        }
        try {
            long connectionTimeout = environment.getProperty("spring.datasource.hikari.connection-timeout", Long.class, 30000L);
            if (connectionTimeout < 250 || connectionTimeout > 30000) {
                throw new IllegalStateException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Anomaly datasource connection timeout must be between 250 and 30000 ms");
        }
        // poll당 조회 TX / auth / CAS 최대3회 / backoff의 보수적인 예산은 215초다 (전용 guide).
        for (String key : List.of("default", "auth-service", "adminNotificationRecipientClient")) {
            var config = feign.getConfig().get(key);
            if (config != null && ((config.getConnectTimeout() != null && (config.getConnectTimeout() <= 0 || config.getConnectTimeout() > 2000))
                || (config.getReadTimeout() != null && (config.getReadTimeout() <= 0 || config.getReadTimeout() > 3000)))) {
                throw new IllegalStateException("Anomaly recipient timeouts exceed consumer poll budget");
            }
        }
    }

    @Bean
    public RequiredTopicsVerifier anomalyDltTopicsVerifier(KafkaAdmin admin, MessagingProperties messaging) {
        return new RequiredTopicsVerifier(admin, topics(messaging));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<byte[], byte[]> anomalyDltListenerContainerFactory(
        ConsumerFactory<?, ?> sharedConsumerFactory, MessagingProperties messaging, AnomalyNotificationProperties properties
    ) {
        Map<String, Object> config = new HashMap<>(sharedConsumerFactory.getConfigurationProperties());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        ConcurrentKafkaListenerContainerFactory<byte[], byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setDeliveryAttemptHeader(false);
        AnomalyDltErrorHandler errorHandler = new AnomalyDltErrorHandler(properties);
        factory.setCommonErrorHandler(errorHandler);
        // 프레임워크가 listener 호출 실패를 출력해도 원문 record를 받지 않도록 메타데이터만 복사한다.
        factory.setRecordInterceptor(new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<byte[], byte[]> intercept(ConsumerRecord<byte[], byte[]> record, Consumer<byte[], byte[]> consumer) {
                return new ConsumerRecord<>(record.topic(), record.partition(), record.offset(), null, null);
            }

            @Override
            public void success(ConsumerRecord<byte[], byte[]> record, Consumer<byte[], byte[]> consumer) {
                errorHandler.succeeded(record);
            }
        });
        factory.setContainerCustomizer(container -> {
            String[] actual = container.getContainerProperties().getTopics();
            if (actual == null || !topics(messaging).equals(Arrays.asList(actual))
                || !(messaging.namespace() + ".operations.anomaly-dlt").equals(container.getGroupId())) {
                throw new IllegalStateException("Invalid anomaly DLT topics or consumer group");
            }
        });
        return factory;
    }

    private static List<String> topics(MessagingProperties messaging) {
        return Arrays.stream(AnomalySource.values()).map(source -> messaging.namespace() + "." + source.topicSuffix()).toList();
    }
}
