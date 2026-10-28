package com.commercefoundry.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.commercefoundry.messaging.consume.ProcessedEventStore;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.messaging.outbox.OutboxStore;
import com.commercefoundry.messaging.properties.MessagingProperties;
import com.commercefoundry.messaging.support.InMemoryOutboxStore;
import com.commercefoundry.messaging.support.InMemoryProcessedEventStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.kafka.core.KafkaTemplate;

/** 배선 — 서비스가 둔 저장소 · 스위치에 따라 릴레이 · 정리 중 무엇을 거는지. 받기만 하는 서비스도 정리가 걸린다(#378). */
class MessagingBackgroundLifecycleTest {

    private final MessagingConfigurer configurer = new MessagingConfigurer();

    private static MessagingProperties properties(boolean enabled, boolean relayEnabled, boolean cleanupEnabled) {
        MessagingProperties.Relay relay = new MessagingProperties.Relay(relayEnabled, Duration.ofSeconds(1), 100, 10, Duration.ofSeconds(5),
            Duration.ofSeconds(30), "shedlock");
        MessagingProperties.Cleanup cleanup = new MessagingProperties.Cleanup(cleanupEnabled, Duration.ofHours(1), Duration.ofDays(7), 1000);
        return new MessagingProperties(enabled, "commerce-foundry.test", "messaging-test", List.of(), 65536,
            new MessagingProperties.Outbox(relay, cleanup), null, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private MessagingBackgroundLifecycle wire(MessagingProperties properties, OutboxStore outboxStore, ProcessedEventStore processedEventStore) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (outboxStore != null) {
            beans.registerSingleton("outboxStore", outboxStore);
        }
        if (processedEventStore != null) {
            beans.registerSingleton("processedEventStore", processedEventStore);
        }
        beans.registerSingleton("kafkaTemplate", mock(KafkaTemplate.class));
        beans.registerSingleton("lockProvider", (LockProvider) configuration -> Optional.empty());
        return configurer.messagingBackgroundLifecycle(properties, beans.getBeanProvider(OutboxStore.class), beans.getBeanProvider(ProcessedEventStore.class),
            (ObjectProvider) beans.getBeanProvider(KafkaTemplate.class), beans.getBeanProvider(LockProvider.class), beans.getBeanProvider(DataSource.class),
            new TopicNames("commerce-foundry.test", "messaging-test"), new EventEnvelopeCodec(65536), beans.getBeanProvider(Clock.class),
            beans.getBeanProvider(MeterRegistry.class));
    }

    @Test
    @DisplayName("받기만 하는 서비스(inbox 만) — 정리만 걸리고 릴레이는 없다")
    void inboxOnlyRunsCleanup() {
        MessagingBackgroundLifecycle lifecycle = wire(properties(true, true, true), null, new InMemoryProcessedEventStore());

        assertThat(lifecycle.runsCleanup()).isTrue();
        assertThat(lifecycle.runsRelay()).isFalse();
    }

    @Test
    @DisplayName("outbox · inbox 를 둔 서비스 — 릴레이 · 정리 둘 다 걸린다")
    void outboxRunsBoth() {
        MessagingBackgroundLifecycle lifecycle = wire(properties(true, true, true), new InMemoryOutboxStore(), new InMemoryProcessedEventStore());

        assertThat(lifecycle.runsRelay()).isTrue();
        assertThat(lifecycle.runsCleanup()).isTrue();
    }

    @Test
    @DisplayName("인스턴스 스위치(outbox.relay.enabled=false) — 릴레이 · 정리 둘 다 없다")
    void relaySwitchOffStopsBoth() {
        MessagingBackgroundLifecycle lifecycle = wire(properties(true, false, true), new InMemoryOutboxStore(), new InMemoryProcessedEventStore());

        assertThat(lifecycle.runsRelay()).isFalse();
        assertThat(lifecycle.runsCleanup()).isFalse();
    }

    @Test
    @DisplayName("메시징 꺼짐 — 릴레이 · 정리 둘 다 없고 start 가 아무것도 돌리지 않는다")
    void messagingOffRunsNothing() {
        MessagingBackgroundLifecycle lifecycle = wire(properties(false, true, true), new InMemoryOutboxStore(), new InMemoryProcessedEventStore());

        lifecycle.start();

        assertThat(lifecycle.runsRelay()).isFalse();
        assertThat(lifecycle.runsCleanup()).isFalse();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    @DisplayName("정리 꺼짐(outbox.cleanup.enabled=false) — 릴레이만 걸린다")
    void cleanupOffRunsRelayOnly() {
        MessagingBackgroundLifecycle lifecycle = wire(properties(true, true, false), new InMemoryOutboxStore(), new InMemoryProcessedEventStore());

        assertThat(lifecycle.runsRelay()).isTrue();
        assertThat(lifecycle.runsCleanup()).isFalse();
    }

    @Test
    @DisplayName("정리 꺼짐 + inbox 만 — 아무것도 걸리지 않는다")
    void cleanupOffInboxOnlyRunsNothing() {
        MessagingBackgroundLifecycle lifecycle = wire(properties(true, true, false), null, new InMemoryProcessedEventStore());

        assertThat(lifecycle.runsRelay()).isFalse();
        assertThat(lifecycle.runsCleanup()).isFalse();
    }

    @Test
    @DisplayName("정리만 걸려도 start · stop 이 돈다 — 전용 스레드 하나")
    void startsAndStopsWithCleanupOnly() {
        MessagingBackgroundLifecycle lifecycle = wire(properties(true, true, true), null, new InMemoryProcessedEventStore());

        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
        lifecycle.stop();

        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(lifecycle.getPhase()).isEqualTo(Integer.MAX_VALUE - 2);
    }
}
