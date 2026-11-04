package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.PaymentAnomalyAlertStateRepository;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyAlertStateProcessor;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@TestConfiguration(proxyBeanMethods = false)
@Import({AnomalyProbeConfig.class, PaymentAnomalyAlertStateAdapter.class, NotificationBatchInsertAdapter.class, PaymentAnomalyAlertStateProcessor.class})
@EnableTransactionManagement
class PaymentAnomalyPersistenceConfig {
    @Bean
    PaymentAnomalyAlertStateRepository anomalyStates(EntityManagerFactory factory) {
        return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory)).getRepository(PaymentAnomalyAlertStateRepository.class);
    }
    @Bean
    SnowflakeIdGenerator anomalyIds() { return new SnowflakeIdGenerator(3, 7); }
    @Bean
    SampleClock anomalyClock() { return new SampleClock(); }

    static final class SampleClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-04T01:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
