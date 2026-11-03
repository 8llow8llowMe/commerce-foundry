package com.commercefoundry.global.config;

import com.commercefoundry.domainlayer.notification.application.service.DltAlertEventFacade;
import com.commercefoundry.global.properties.DltAlertProperties;
import com.commercefoundry.messaging.config.RequiredTopicsVerifier;
import com.commercefoundry.messaging.event.TopicNames;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Slf4j
@Configuration
@EnableConfigurationProperties(DltAlertProperties.class)
public class DltAlertConfig {
    @Bean
    public List<String> dltAlertTopics(TopicNames names) {
        return DltAlertEventFacade.TOPICS.stream().map(names::qualified).toList();
    }

    @Bean
    @ConditionalOnBooleanProperty(name = {"operations.notifications.dlt-alert.enabled", "commerce.messaging.enabled"})
    public RequiredTopicsVerifier dltAlertTopicsVerifier(KafkaAdmin admin, List<String> dltAlertTopics) {
        return new RequiredTopicsVerifier(admin, dltAlertTopics);
    }

    @Bean
    @ConditionalOnBooleanProperty(name = {"operations.notifications.dlt-alert.enabled", "commerce.messaging.enabled"})
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ConcurrentKafkaListenerContainerFactory<Object, Object> dltAlertListenerFactory(ConsumerFactory consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        var handler = new DefaultErrorHandler(new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(java.util.Map.of(Exception.class, true), true);
        handler.setRetryListeners((record, exception, attempt) -> log.warn("DLT alert persistence unavailable; retry scheduled"));
        // DLT alert metadata only; see anomaly-notifications.md.
        handler.setSeekAfterError(false);
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.TRACE);
        factory.setCommonErrorHandler(handler);
        return factory;
    }
}
