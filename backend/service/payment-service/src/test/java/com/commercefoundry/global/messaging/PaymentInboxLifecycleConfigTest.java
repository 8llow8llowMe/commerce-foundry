package com.commercefoundry.global.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.commercefoundry.messaging.config.MessagingBackgroundLifecycle;
import com.commercefoundry.messaging.config.MessagingConfigurer;
import com.commercefoundry.messaging.consume.ProcessedEventStore;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.messaging.outbox.OutboxStore;
import com.commercefoundry.messaging.properties.MessagingProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.kafka.core.KafkaTemplate;

/** Service-local wiring fixture: production core configurer + payment inbox implementation, no listener or broker. */
class PaymentInboxLifecycleConfigTest {
    @ParameterizedTest
    @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true",
        "true,false,false", "true,false,true", "true,true,false", "true,true,true"})
    @SuppressWarnings({"rawtypes", "unchecked"})
    void cleanupOnlyRunsWhenAllThreeSwitchesAreOnAndIncludesPaymentInbox(boolean messaging, boolean relay, boolean cleanup) {
        var repository = mock(ProcessedEventRepository.class);
        var inbox = spy(new JpaProcessedEventStore(repository, new SnowflakeIdGenerator(0, 0)));
        var outbox = mock(OutboxStore.class);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("paymentInbox", inbox);
        beans.registerSingleton("paymentOutbox", outbox);
        beans.registerSingleton("kafkaTemplate", mock(KafkaTemplate.class));
        beans.registerSingleton("lockProvider", (LockProvider) configuration -> Optional.of(() -> {}));
        beans.registerSingleton("clock", Clock.fixed(PaymentProcessedEventStoreContract.NOW, ZoneOffset.UTC));
        var properties = new MessagingProperties(messaging, "commerce-foundry.test", "payment-service", List.of(), 65536,
            new MessagingProperties.Outbox(new MessagingProperties.Relay(relay, Duration.ofDays(1), 100, 10, Duration.ofSeconds(1),
                Duration.ofSeconds(30), "payment.shedlock"),
                new MessagingProperties.Cleanup(cleanup, Duration.ofMillis(10), Duration.ofDays(7), 100)), null, null);
        MessagingBackgroundLifecycle lifecycle = new MessagingConfigurer().messagingBackgroundLifecycle(properties,
            beans.getBeanProvider(OutboxStore.class), beans.getBeanProvider(ProcessedEventStore.class),
            (ObjectProvider) beans.getBeanProvider(KafkaTemplate.class), beans.getBeanProvider(LockProvider.class),
            beans.getBeanProvider(DataSource.class), new TopicNames("commerce-foundry.test", "payment-service"),
            new EventEnvelopeCodec(65536), beans.getBeanProvider(Clock.class), beans.getBeanProvider(MeterRegistry.class));
        try {
            assertThat(lifecycle.runsRelay()).isEqualTo(messaging && relay);
            assertThat(lifecycle.runsCleanup()).isEqualTo(messaging && relay && cleanup);
            lifecycle.start();
            if (messaging && relay && cleanup) {
                verify(inbox, timeout(2000).atLeastOnce()).deleteProcessedBefore(PaymentProcessedEventStoreContract.NOW.minus(Duration.ofDays(7)), 100);
                verify(outbox, atLeastOnce()).deletePublishedBefore(PaymentProcessedEventStoreContract.NOW.minus(Duration.ofDays(7)), 100);
                verify(repository, atLeastOnce()).findIdsProcessedBefore(any(), any());
            } else {
                verify(inbox, after(100).never()).deleteProcessedBefore(any(), anyInt());
                verify(outbox, org.mockito.Mockito.never()).deletePublishedBefore(any(), anyInt());
            }
        } finally {
            lifecycle.stop();
        }
    }
}
