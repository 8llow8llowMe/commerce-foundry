package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.adapter.in.event.PaymentApprovedEventListener;
import com.commercefoundry.domainlayer.order.application.port.in.OrderPaymentEventUseCase;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import java.util.Map;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;

class OrderPaymentConsumerConfigTest {
    private final KafkaAdmin admin = mock(KafkaAdmin.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(OrderPaymentConsumerConfig.class)
        .withBean(KafkaAdmin.class, () -> admin).withBean(TopicNames.class, () -> new TopicNames("commerce-foundry.test", "order"));

    @Test
    void defaultOffPreservesExistingMessagingWithoutNewTopics() {
        runner.withPropertyValues("commerce.messaging.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("paymentApprovalTopicsVerifier");
            verify(admin, never()).describeTopics(any(String[].class));
        });
    }

    @Test
    void messagingOffNeverRequiresApprovalTopics() {
        runner.withPropertyValues("commerce.messaging.enabled=false", "order.payment-approval.consumer.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("paymentApprovalTopicsVerifier");
            verify(admin, never()).describeTopics(any(String[].class));
        });
    }

    @Test
    void consumerGateOnFailsStartupIfTopicMissing() {
        when(admin.describeTopics(any(String[].class))).thenThrow(new IllegalStateException("MISSING_TOPIC_PRIVATE_SENTINEL"));
        runner.withPropertyValues("commerce.messaging.enabled=true", "order.payment-approval.consumer.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageNotContaining("MISSING_TOPIC_PRIVATE_SENTINEL");
        });
    }

    @Test
    void consumerGateOnVerifiesAllTopicsAndDlts() {
        when(admin.describeTopics(any(String[].class))).thenReturn(Map.of());
        runner.withPropertyValues("commerce.messaging.enabled=true", "order.payment-approval.consumer.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasBean("paymentApprovalTopicsVerifier");
            verify(admin).describeTopics("commerce-foundry.test.payment.payment-approved", "commerce-foundry.test.payment.payment-approved.dlt",
                "commerce-foundry.test.order.order-paid", "commerce-foundry.test.order.order-paid.dlt",
                "commerce-foundry.test.order.order-payment-rejected", "commerce-foundry.test.order.order-payment-rejected.dlt");
        });
    }

    @Test
    void realKafkaListenerAnnotationRegistersResolvedTopicAndGroupOnlyWithBothGates() {
        var registration = runner.withUserConfiguration(ListenerRegistration.class, PaymentApprovedEventListener.class);
        registration.withPropertyValues("commerce.messaging.enabled=true", "order.payment-approval.consumer.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PaymentApprovedEventListener.class);
            var container = context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("payment-approved");
            assertThat(container).isNotNull();
            assertThat(container.getContainerProperties().getTopics()).containsExactly("commerce-foundry.test.payment.payment-approved");
            assertThat(container.getGroupId()).isEqualTo("commerce-foundry.test.order.payment-approved");
            assertThat(container.isRunning()).isFalse(); // 선언등록만 확인한다. 브로커 업무 E2E는 DB계약에서 실행한다.
        });
        registration.withPropertyValues("commerce.messaging.enabled=true", "order.payment-approval.consumer.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PaymentApprovedEventListener.class);
            assertThat(context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers()).isEmpty();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    static class ListenerRegistration {
        @Bean EventEnvelopeCodec eventEnvelopeCodec() { return new EventEnvelopeCodec(65536); }
        @Bean OrderPaymentEventUseCase orderPaymentEventUseCase() { return mock(OrderPaymentEventUseCase.class); }
        @Bean ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
            var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(Map.of("bootstrap.servers", "127.0.0.1:1"),
                new StringDeserializer(), new StringDeserializer()));
            factory.setAutoStartup(false);
            return factory;
        }
    }
}
