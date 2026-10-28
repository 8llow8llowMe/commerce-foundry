package com.commercefoundry.messaging.config;

import com.commercefoundry.messaging.cleanup.MessagingCleanup;
import com.commercefoundry.messaging.consume.IdempotentEventProcessor;
import com.commercefoundry.messaging.consume.MessagingErrorHandlers;
import com.commercefoundry.messaging.consume.ProcessedEventStore;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.messaging.outbox.OutboxRelay;
import com.commercefoundry.messaging.outbox.OutboxStore;
import com.commercefoundry.messaging.properties.MessagingProperties;
import com.commercefoundry.messaging.publish.EventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbc.JdbcLockProvider;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Kafka 메시징을 쓰는 서비스의 {@code *BeansConfig} 가 {@code @Import} 한다. 프로퍼티는 {@link MessagingPropertiesConfig} 가 등록한다.
 * 컴포넌트 스캔에 걸리지 않게 {@code @Configuration} 을 달지 않는다 ({@code StorageConfigurer} 와 같다).
 *
 * <p>접속은 Boot {@code spring.kafka.*} 자동설정을 그대로 쓰고, 여기서는 모듈이 강제하는 값만 덮는다 — 멱등 프로듀서 · {@code acks=all},
 * 자동 커밋 끔 · 처리 뒤 레코드 단위 오프셋 커밋(ack RECORD), 컨슈머 재시도 · DLT. 리스너 컨테이너는 메시징이 켜졌을 때만 뜬다.
 *
 * <p>빈은 메시징이 꺼져 있어도 모두 있다 — 서비스 코드는 켜짐 여부를 분기하지 않는다. 꺼져 있으면 발행은 outbox 저장까지만 하고(릴레이 정지),
 * 리스너는 시작하지 않는다.
 */
@Slf4j
public class MessagingConfigurer {

    /** static — {@code BeanFactoryPostProcessor} 는 설정 클래스 인스턴스보다 먼저 만들어져야 한다. */
    @Bean
    public static MessagingStartupGuard messagingStartupGuard(Environment environment) {
        return new MessagingStartupGuard(environment);
    }

    @Bean
    public EventEnvelopeCodec eventEnvelopeCodec(MessagingProperties messagingProperties) {
        return new EventEnvelopeCodec(messagingProperties.maxPayloadBytes());
    }

    /** 꺼져 있고 namespace 가 비었으면 자리값 namespace 를 쓴다 — 리스너 토픽 이름은 풀리지만 컨테이너가 뜨지 않는다. */
    @Bean
    public TopicNames topicNames(MessagingProperties messagingProperties, Environment environment) {
        String namespace = messagingProperties.namespace() == null ? "commerce-foundry.unconfigured" : messagingProperties.namespace();
        return new TopicNames(namespace, serviceName(messagingProperties, environment));
    }

    @Bean
    public EventPublisher eventPublisher(
        ObjectProvider<OutboxStore> outboxStore, EventEnvelopeCodec eventEnvelopeCodec, MessagingProperties messagingProperties,
        Environment environment, ObjectProvider<Clock> clock
    ) {
        return new EventPublisher(outboxStore::getIfAvailable, eventEnvelopeCodec, serviceName(messagingProperties, environment),
            clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    public IdempotentEventProcessor idempotentEventProcessor(
        ObjectProvider<ProcessedEventStore> processedEventStore, PlatformTransactionManager transactionManager, ObjectProvider<Clock> clock
    ) {
        return new IdempotentEventProcessor(processedEventStore::getIfAvailable, new TransactionTemplate(transactionManager),
            clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * 멱등 프로듀서 · {@code acks=all} — 설정으로 덮지 못한다(가드가 막는다). 직렬화는 문자열로 고정한다.
     * {@code max.block.ms} 를 발행 대기 상한에 맞춘다 — 토픽이 없을 때 {@code send()} 가 메타데이터를 기다리며 기본 60초 동안 릴레이를 붙잡지 않게.
     */
    @Bean
    public DefaultKafkaProducerFactoryCustomizer messagingProducerFactoryCustomizer(MessagingProperties messagingProperties) {
        return factory -> factory.updateConfigs(Map.of(
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
            ProducerConfig.MAX_BLOCK_MS_CONFIG, messagingProperties.outbox().relay().publishTimeout().toMillis(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        ));
    }

    /**
     * 자동 커밋 끔 · 그룹 첫 생성 때 처음부터 · 토픽 자동 생성 요청 안 함. 역직렬화는 문자열로 고정한다 — JSON 역직렬화기의 타입 헤더({@code __TypeId__})
     * 선택은 누구나 헤더를 붙여 produce 할 수 있는 클러스터에서 열지 않는다. 봉투는 {@link EventEnvelopeCodec} 이 읽는다.
     */
    @Bean
    public DefaultKafkaConsumerFactoryCustomizer messagingConsumerFactoryCustomizer() {
        return factory -> factory.updateConfigs(Map.of(
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        ));
    }

    /**
     * Boot 의 같은 이름 빈({@code @ConditionalOnMissingBean})을 대신한다 — {@code spring.kafka.listener.*} 는 Boot 설정기로 그대로 적용하고,
     * ack 모드 RECORD(처리가 끝난 레코드만 커밋) · 재시도 · DLT 오류 처리 · 켜짐에 따른 자동 시작을 덮는다.
     */
    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ObjectProvider<ConsumerFactory<?, ?>> consumerFactory,
        ObjectProvider<KafkaTemplate<?, ?>> kafkaTemplate, MessagingProperties messagingProperties, ObjectProvider<MeterRegistry> meterRegistry,
        TopicNames topicNames
    ) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, (ConsumerFactory) consumerFactory.getObject());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(MessagingErrorHandlers.create(kafkaTemplate.getObject(), messagingProperties.retry(), meterRegistry.getIfAvailable()));
        factory.setAutoStartup(messagingProperties.enabled());
        factory.setContainerCustomizer(container -> verifyListener(container, messagingProperties, topicNames));
        if (!messagingProperties.enabled()) {
            log.info("kafka messaging disabled — listeners do not start, events stay in outbox. {}=false", MessagingProperties.ENABLED);
        }
        return factory;
    }

    /**
     * 리스너마다 만들 때 본다.
     * <ul>
     *   <li>꺼져 있으면 {@code @KafkaListener(autoStartup = "true")} 를 적어도 뜨지 않게 덮는다.</li>
     *   <li>켜져 있으면 구독 토픽 · 그룹이 이 환경 namespace 로 시작해야 한다 — 리터럴로 다른 환경 토픽 · 그룹을 적은 리스너를 기동에서 막는다.
     *       토픽 패턴 · 파티션 직접 지정은 이름을 검사할 수 없어 쓰지 않는다.</li>
     * </ul>
     */
    static void verifyListener(ConcurrentMessageListenerContainer<?, ?> container, MessagingProperties messagingProperties, TopicNames topicNames) {
        if (!messagingProperties.enabled()) {
            container.setAutoStartup(false);
            return;
        }
        String prefix = topicNames.namespace() + ".";
        String[] topics = container.getContainerProperties().getTopics();
        if (topics == null || topics.length == 0) {
            throw new IllegalStateException("리스너는 토픽 이름으로 구독해야 합니다(패턴 · 파티션 지정 금지). listener=" + container.getListenerId());
        }
        for (String topic : topics) {
            if (!topic.startsWith(prefix)) {
                throw new IllegalStateException("리스너 토픽이 이 환경 namespace 로 시작하지 않습니다 — TopicNames 로 만드세요. listener=" + container.getListenerId());
            }
        }
        String groupId = container.getGroupId();
        if (groupId == null || !groupId.startsWith(prefix)) {
            throw new IllegalStateException("리스너 그룹이 이 환경 namespace 로 시작하지 않습니다 — TopicNames.consumerGroup 으로 만드세요. listener=" + container.getListenerId());
        }
    }

    /**
     * 메시징 백그라운드 — outbox 릴레이와 기록 정리(outbox · inbox)를 한 스레드에서 돌린다.
     * <ul>
     *   <li>메시징({@code enabled}) · 인스턴스 스위치({@code outbox.relay.enabled}) 중 하나라도 꺼져 있으면 둘 다 돌지 않는다.</li>
     *   <li>릴레이는 이 서비스가 {@link OutboxStore} 를 두었을 때만 돈다.</li>
     *   <li>정리는 {@code outbox.cleanup.enabled} 이고 {@link OutboxStore} · {@link ProcessedEventStore} 중 하나라도 있으면 돈다 —
     *       받기만 하는 서비스도 처리 기록이 지워진다(#378).</li>
     * </ul>
     * 단일 실행 락은 ShedLock JDBC 테이블({@code commerce.messaging.outbox.relay.lock-table}, 서비스 스키마)이고 릴레이 · 정리가 함께 쓴다.
     * {@code LockProvider} 빈이 있으면 그것을 쓴다(테스트 · 다른 락 저장소).
     */
    @Bean
    @SuppressWarnings("unchecked")
    public MessagingBackgroundLifecycle messagingBackgroundLifecycle(
        MessagingProperties messagingProperties, ObjectProvider<OutboxStore> outboxStore, ObjectProvider<ProcessedEventStore> processedEventStore,
        ObjectProvider<KafkaTemplate<?, ?>> kafkaTemplate, ObjectProvider<LockProvider> lockProvider, ObjectProvider<DataSource> dataSource, TopicNames topicNames,
        EventEnvelopeCodec eventEnvelopeCodec, ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meterRegistry
    ) {
        if (!messagingProperties.enabled() || !messagingProperties.outbox().relay().enabled()) {
            return new MessagingBackgroundLifecycle(null, null, messagingProperties);
        }
        OutboxStore store = outboxStore.getIfAvailable();
        ProcessedEventStore processed = processedEventStore.getIfAvailable();
        boolean runRelay = store != null;
        boolean runCleanup = messagingProperties.outbox().cleanup().enabled() && (store != null || processed != null);
        if (!runRelay && !runCleanup) {
            return new MessagingBackgroundLifecycle(null, null, messagingProperties);
        }
        Clock runClock = clock.getIfAvailable(Clock::systemUTC);
        LockingTaskExecutor lockingTaskExecutor =
            new DefaultLockingTaskExecutor(lockProvider.getIfAvailable(() -> jdbcLockProvider(dataSource, messagingProperties)));
        OutboxRelay relay = runRelay
            ? new OutboxRelay(store, (KafkaOperations<String, String>) kafkaTemplate.getObject(), lockingTaskExecutor, topicNames, eventEnvelopeCodec,
                messagingProperties, runClock, meterRegistry.getIfAvailable())
            : null;
        MessagingCleanup cleanup = runCleanup ? new MessagingCleanup(store, processed, lockingTaskExecutor, messagingProperties, runClock) : null;
        return new MessagingBackgroundLifecycle(relay, cleanup, messagingProperties);
    }

    /** 켜져 있으면 쓰는 토픽이 있는지 기동 때 본다 — 앱은 토픽을 만들지 않는다. */
    @Bean
    public RequiredTopicsVerifier requiredTopicsVerifier(MessagingProperties messagingProperties, ObjectProvider<KafkaAdmin> kafkaAdmin, TopicNames topicNames) {
        List<String> topics = messagingProperties.enabled()
            ? messagingProperties.requiredTopics().stream().map(topicNames::qualified).toList()
            : List.of();
        return new RequiredTopicsVerifier(kafkaAdmin.getObject(), topics);
    }

    /** 서비스가 LockProvider 빈을 따로 두지 않았으면 서비스 DataSource 의 ShedLock 테이블을 쓴다. */
    private static LockProvider jdbcLockProvider(ObjectProvider<DataSource> dataSource, MessagingProperties messagingProperties) {
        DataSource lockDataSource = dataSource.getIfAvailable();
        if (lockDataSource == null) {
            throw new IllegalStateException("메시징 릴레이 · 정리의 단일 실행 락에 DataSource 가 필요합니다.");
        }
        return new JdbcLockProvider(lockDataSource, messagingProperties.outbox().relay().lockTable());
    }

    private static String serviceName(MessagingProperties messagingProperties, Environment environment) {
        if (messagingProperties.service() != null) {
            return messagingProperties.service();
        }
        String applicationName = environment.getProperty("spring.application.name");
        return applicationName == null || applicationName.isBlank() ? "unknown-service" : applicationName;
    }
}
