package com.commercefoundry.domainlayer.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.notification.application.command.AnomalyRecordCommand;
import com.commercefoundry.domainlayer.notification.application.port.out.AdminNotificationRecipientQueryPort;
import com.commercefoundry.domainlayer.notification.application.port.out.AnomalyRepositoryPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRepositoryPort;
import com.commercefoundry.domainlayer.notification.application.service.processor.AnomalyNotificationProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.AnomalySource;
import com.commercefoundry.domainlayer.notification.domain.model.AnomalyState;
import com.commercefoundry.global.properties.AnomalyNotificationProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class AnomalyNotificationPostgresTest extends AnomalyNotificationFlowTestBase {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    @Autowired AnomalyRepositoryPort anomalyPort;
    @Autowired NotificationRepositoryPort notificationPort;
    @Autowired AdminNotificationRecipientQueryPort recipients;
    @Autowired AnomalyNotificationProperties properties;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Environment environment;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(TOPICS.stream().map(topic -> new NewTopic(topic, 1, (short) 1)).toList()).all().get(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Test Kafka topic provisioning failed", exception);
        }
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void independentProcessorInstancesConvergeOnOneEmissionAcrossInitialUniqueAndVersionRaces() throws Exception {
        CyclicBarrier simultaneousReads = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        AnomalyRepositoryPort racingPort = new AnomalyRepositoryPort() {
            @Override public boolean alreadyProcessed(AnomalyRecordCommand command) { return anomalyPort.alreadyProcessed(command); }
            @Override public Optional<AnomalyState> findState(String key) {
                var state = anomalyPort.findState(key);
                if (reads.incrementAndGet() <= 2) {
                    try { simultaneousReads.await(10, TimeUnit.SECONDS); }
                    catch (Exception exception) { throw new IllegalStateException(exception); }
                }
                return state;
            }
            @Override public void saveStateAndFlush(AnomalyState state) { anomalyPort.saveStateAndFlush(state); }
            @Override public void insertRecord(long id, AnomalyRecordCommand command, Instant now) { anomalyPort.insertRecord(id, command, now); }
            @Override public int deleteProcessedBefore(Instant cutoff) { return anomalyPort.deleteProcessedBefore(cutoff); }
        };
        var instanceA = instance(racingPort, 20);
        var instanceB = instance(racingPort, 21);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> instanceA.process(new AnomalyRecordCommand(AnomalySource.ORDER_CANCELED, CANCELED, 0, 700001)));
            var second = executor.submit(() -> instanceB.process(new AnomalyRecordCommand(AnomalySource.ORDER_CANCELED, CANCELED, 1, 700002)));
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }
        assertThat(states.count()).isEqualTo(1);
        assertThat(records.count()).isEqualTo(2);
        assertThat(notifications.count()).isEqualTo(2);
        long previousVersion = states.findAll().getFirst().getVersion();
        clock.now.set(clock.instant().plusSeconds(3600));
        reads.set(0);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> instanceA.process(new AnomalyRecordCommand(AnomalySource.ORDER_CANCELED, CANCELED, 0, 700003)));
            var second = executor.submit(() -> instanceB.process(new AnomalyRecordCommand(AnomalySource.ORDER_CANCELED, CANCELED, 1, 700004)));
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }
        assertThat(records.count()).isEqualTo(4);
        assertThat(notifications.count()).isEqualTo(4);
        assertThat(states.findAll().getFirst().getVersion()).isGreaterThan(previousVersion);
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname = 'operations' and tablename = 'notification_anomaly_record'", String.class))
            .contains("idx_notification_anomaly_record_processed_at");
        var duplicate = new AnomalyRecordCommand(AnomalySource.ORDER_CANCELED, CANCELED, 0, 700001);
        instanceB.process(duplicate);
        assertThat(notifications.count()).isEqualTo(4);
    }

    private AnomalyNotificationFacade instance(AnomalyRepositoryPort port, long workerId) {
        var processor = new AnomalyNotificationProcessor(port, recipients, notificationPort, new SnowflakeIdGenerator(1, workerId), properties, clock, events);
        ProxyFactory proxy = new ProxyFactory(processor);
        proxy.setProxyTargetClass(true);
        var attributes = new AnnotationTransactionAttributeSource();
        attributes.setEmbeddedValueResolver(environment::resolveRequiredPlaceholders);
        proxy.addAdvice(new TransactionInterceptor(transactions, attributes));
        return new AnomalyNotificationFacade((AnomalyNotificationProcessor) proxy.getProxy());
    }
}
