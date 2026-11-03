package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.payment.adapter.in.event.OrderPaymentReplyEventListener;
import com.commercefoundry.domainlayer.payment.application.port.in.OrderPaymentReplyEventUseCase;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import java.util.Map;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;

/** 회신 컨슈머 게이트(#308 PR-C) — 두 스위치가 모두 켜졌을 때만 토픽 확인 · 리스너가 생긴다. */
class PaymentOrderResultConsumerConfigTest {

    private static final String GATE = "payment.order-result.consumer.enabled";
    private final KafkaAdmin admin = mock(KafkaAdmin.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PaymentOrderResultConsumerConfig.class)
        .withBean(KafkaAdmin.class, () -> admin).withBean(TopicNames.class, () -> new TopicNames("commerce-foundry.test", "payment"));

    @BeforeEach
    void resetAdmin() {
        reset(admin);
    }

    @Test
    void gateIsClosedByDefaultAndKeepsExistingStartupContract() {
        runner.withPropertyValues("commerce.messaging.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("orderPaymentResultTopicsVerifier");
            verify(admin, never()).describeTopics(any(String[].class));
        });
    }

    @Test
    void messagingOffNeverRequiresReplyTopics() {
        runner.withPropertyValues("commerce.messaging.enabled=false", GATE + "=true").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("orderPaymentResultTopicsVerifier");
            verify(admin, never()).describeTopics(any(String[].class));
        });
    }

    @Test
    void gateOnFailsStartupWithoutLeakingCauseWhenTopicMissing() {
        when(admin.describeTopics(any(String[].class))).thenThrow(new IllegalStateException("MISSING_TOPIC_PRIVATE_SENTINEL"));
        runner.withPropertyValues("commerce.messaging.enabled=true", GATE + "=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageNotContaining("MISSING_TOPIC_PRIVATE_SENTINEL");
        });
    }

    @Test
    void gateOnVerifiesBothReplyTopicsAndDlts() {
        when(admin.describeTopics(any(String[].class))).thenReturn(Map.of());
        runner.withPropertyValues("commerce.messaging.enabled=true", GATE + "=true").run(context -> {
            assertThat(context).hasNotFailed().hasBean("orderPaymentResultTopicsVerifier");
            verify(admin).describeTopics("commerce-foundry.test.order.order-paid", "commerce-foundry.test.order.order-paid.dlt",
                "commerce-foundry.test.order.order-payment-rejected", "commerce-foundry.test.order.order-payment-rejected.dlt");
        });
    }

    @Test
    void realKafkaListenerRegistersReplyTopicsAndGroupOnlyWithBothGates() {
        when(admin.describeTopics(any(String[].class))).thenReturn(Map.of());
        var registration = runner.withUserConfiguration(ListenerRegistration.class, OrderPaymentReplyEventListener.class);
        registration.withPropertyValues("commerce.messaging.enabled=true", GATE + "=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(OrderPaymentReplyEventListener.class);
            var container = context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("order-payment-result");
            assertThat(container).isNotNull();
            assertThat(container.getContainerProperties().getTopics())
                .containsExactly("commerce-foundry.test.order.order-paid", "commerce-foundry.test.order.order-payment-rejected");
            assertThat(container.getGroupId()).isEqualTo("commerce-foundry.test.payment.order-payment-result");
            assertThat(container.isRunning()).isFalse(); // 선언 등록만 본다. 브로커 E2E 는 OrderPaymentReplyConsumerContract 가 돈다
        });
        registration.withPropertyValues("commerce.messaging.enabled=true", GATE + "=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(OrderPaymentReplyEventListener.class);
            assertThat(context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers()).isEmpty();
        });
        registration.withPropertyValues("commerce.messaging.enabled=false", GATE + "=true").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(OrderPaymentReplyEventListener.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    static class ListenerRegistration {
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
