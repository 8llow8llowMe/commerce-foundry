package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.global.properties.AnomalyNotificationProperties;
import com.commercefoundry.messaging.config.RequiredTopicsVerifier;
import com.commercefoundry.messaging.properties.MessagingProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.mock.env.MockEnvironment;

class AnomalyDltListenerConfigTest {
    @Test
    void defaultOffRequiresNeitherKafkaNorRecipientClientConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(AnomalyDltListenerConfig.class, AnomalyNotificationPropertiesConfig.class, AnomalyNotificationPushConfig.class)
            .run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(AnomalyDltListenerConfig.class).doesNotHaveBean(AnomalyNotificationPushConfig.class);
                assertThat(context.getBean(AnomalyNotificationProperties.class).enabled()).isFalse();
            });
    }

    @Test
    void enabledRequiresMessagingAndBoundedRecipientTimeouts() {
        assertThatThrownBy(() -> new AnomalyDltListenerConfig(messaging(false), new FeignClientProperties(), new MockEnvironment()))
            .hasMessage("Anomaly notifications require enabled Kafka messaging");
        var feign = new FeignClientProperties();
        var timeout = new FeignClientProperties.FeignClientConfiguration();
        timeout.setReadTimeout(60000);
        feign.getConfig().put("adminNotificationRecipientClient", timeout);
        assertThatThrownBy(() -> new AnomalyDltListenerConfig(messaging(true), feign, new MockEnvironment()))
            .hasMessage("Anomaly recipient timeouts exceed consumer poll budget");
        assertThatThrownBy(() -> new AnomalyDltListenerConfig(messaging(true), new FeignClientProperties(),
            new MockEnvironment().withProperty("spring.datasource.hikari.connection-timeout", "30001")))
            .hasMessage("Anomaly datasource connection timeout must be between 250 and 30000 ms");
        var longNamespace = new MessagingProperties(true, "a".repeat(250) + ".test", "operations", List.of(), 65536, null, null, null);
        assertThatThrownBy(() -> new AnomalyDltListenerConfig(longNamespace, new FeignClientProperties(), new MockEnvironment()))
            .hasMessage("Anomaly Kafka topic or group name exceeds supported length");
    }

    @Test
    void dedicatedFactoryEnforcesCommitPollAndExactTopicGroupAndVerifierChecksThreeDlts() {
        var messaging = messaging(true);
        var config = new AnomalyDltListenerConfig(messaging, new FeignClientProperties(), new MockEnvironment());
        ConsumerFactory<?, ?> shared = mock(ConsumerFactory.class);
        when(shared.getConfigurationProperties()).thenReturn(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1"));
        var factory = config.anomalyDltListenerContainerFactory(shared, messaging,
            new AnomalyNotificationProperties(true, Duration.ofHours(1), Duration.ofDays(8), 1000, 3, Duration.ZERO));
        factory.getContainerProperties().setGroupId("commerce-foundry.test.operations.anomaly-dlt");
        String[] topics = {"commerce-foundry.test.payment.payment-approved.dlt", "commerce-foundry.test.order.order-expired.dlt",
            "commerce-foundry.test.order.order-canceled.dlt"};
        var container = factory.createContainer(topics);
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(container.getCommonErrorHandler().isAckAfterHandle()).isFalse();
        var consumer = factory.getConsumerFactory().getConfigurationProperties();
        assertThat(consumer).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest").containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1)
            .containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000).containsEntry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        assertThatThrownBy(() -> factory.createContainer("commerce-foundry.other.payment.payment-approved.dlt"))
            .hasMessage("Invalid anomaly DLT topics or consumer group");
        KafkaAdmin admin = mock(KafkaAdmin.class);
        RequiredTopicsVerifier verifier = config.anomalyDltTopicsVerifier(admin, messaging);
        verifier.afterSingletonsInstantiated();
        verify(admin).describeTopics(topics);
        when(admin.describeTopics(topics)).thenThrow(new IllegalStateException("private-broker-error"));
        assertThatThrownBy(verifier::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("private-broker-error").hasNoCause();
    }

    private static MessagingProperties messaging(boolean enabled) {
        return new MessagingProperties(enabled, "commerce-foundry.test", "operations", List.of(), 65536, null, null, null);
    }
}
