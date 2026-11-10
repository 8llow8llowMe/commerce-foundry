package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.payment.adapter.in.event.OrderPaymentReplyEventListener;
import com.commercefoundry.domainlayer.payment.application.port.in.OrderPaymentReplyEventUseCase;
import com.commercefoundry.domainlayer.payment.adapter.out.policy.DeferredPaymentCancelPolicy;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPolicy;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAutoCancelProcessor;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import java.util.Map;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;

class PaymentOrderResultConsumerConfigTest {
    final KafkaAdmin admin = mock(KafkaAdmin.class);
    final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(PaymentOrderResultConsumerConfig.class, ListenerBeans.class, OrderPaymentReplyEventListener.class)
        .withBean(KafkaAdmin.class, () -> admin).withBean(TopicNames.class, () -> new TopicNames("test", "payment"));

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false"})
    void eitherGateOffRegistersNoConsumerOrRequiredReplyTopics(boolean messaging, boolean consumer) {
        runner.withPropertyValues("commerce.messaging.enabled=" + messaging, "payment.order-result.consumer.enabled=" + consumer).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(OrderPaymentReplyEventListener.class).doesNotHaveBean("orderPaymentResultTopicsVerifier");
            assertThat(context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers()).isEmpty();
            verify(admin, never()).describeTopics(any(String[].class));
        });
    }
    @Test
    void absentConsumerGateIsOffEvenWithMessagingOn() {
        runner.withPropertyValues("commerce.messaging.enabled=true").run(context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(OrderPaymentReplyEventListener.class).doesNotHaveBean("orderPaymentResultTopicsVerifier"));
    }
    @Test
    void bothGatesResolveExactTopicsAndSharedInboxGroup() {
        when(admin.describeTopics(any(String[].class))).thenReturn(Map.of());
        runner.withPropertyValues("commerce.messaging.enabled=true", "payment.order-result.consumer.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(OrderPaymentReplyEventListener.class);
            var container = context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("order-payment-result");
            assertThat(container.getContainerProperties().getTopics()).containsExactly("test.order.order-paid", "test.order.order-payment-rejected");
            assertThat(container.getGroupId()).isEqualTo("test.payment.order-payment-result");
            assertThat(container.isRunning()).isFalse();
            verify(admin).describeTopics("test.order.order-paid", "test.order.order-paid.dlt", "test.order.order-payment-rejected", "test.order.order-payment-rejected.dlt");
        });
    }
    @Test
    void missingRequiredTopicFailsStartupWithoutPrivateCause() {
        when(admin.describeTopics(any(String[].class))).thenThrow(new IllegalStateException("PRIVATE_KAFKA_SENTINEL"));
        runner.withPropertyValues("commerce.messaging.enabled=true", "payment.order-result.consumer.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageNotContaining("PRIVATE_KAFKA_SENTINEL");
        });
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void existingDeferredPolicyRemainsTheOnlyPolicyAndConsumerGateDoesNotOpenAutoCancel(boolean enabled) {
        var processor = mock(PaymentAutoCancelProcessor.class);
        runner.withUserConfiguration(DeferredPaymentCancelPolicy.class).withBean(PaymentAutoCancelProcessor.class, () -> processor)
            .withPropertyValues("payment.auto-cancel.enabled=" + enabled, "payment.order-result.consumer.enabled=true").run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(PaymentCancelPolicy.class);
                assertThat(context.getBean(PaymentCancelPolicy.class)).isInstanceOf(DeferredPaymentCancelPolicy.class);
                context.getBean(PaymentCancelPolicy.class).onRejected(42L);
                if (enabled) verify(processor).enqueue(42L); else verifyNoInteractions(processor);
            });
    }
    @Configuration(proxyBeanMethods = false) @EnableKafka
    static class ListenerBeans {
        @Bean EventEnvelopeCodec eventEnvelopeCodec() { return new EventEnvelopeCodec(65536); }
        @Bean OrderPaymentReplyEventUseCase orderPaymentReplyEventUseCase() { return mock(OrderPaymentReplyEventUseCase.class); }
        @Bean ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
            var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(Map.of("bootstrap.servers", "127.0.0.1:1"),
                new StringDeserializer(), new StringDeserializer()));
            factory.setAutoStartup(false);
            return factory;
        }
    }
}
