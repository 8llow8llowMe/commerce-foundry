package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.LowStockAlertSkuRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.LowStockAlertStateRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.application.service.processor.LowStockAlertStateProcessor;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.LongSupplier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 품절 임박 저장 경계 JPA slice. 스케줄러 · Feign · Kafka · 애플리케이션 component scan 없음. */
@TestConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class, TransactionAutoConfiguration.class})
@EnableJpaAuditing
@EnableTransactionManagement
@EntityScan(basePackageClasses = NotificationEntity.class)
@Import({LowStockAlertStateAdapter.class, NotificationBatchInsertAdapter.class, LowStockAlertStateProcessor.class})
class LowStockAlertPersistenceConfig {
    @Bean
    LowStockAlertStateRepository lowStockStates(EntityManagerFactory factory) {
        return repositories(factory).getRepository(LowStockAlertStateRepository.class);
    }

    @Bean
    LowStockAlertSkuRepository lowStockSkus(EntityManagerFactory factory) {
        return repositories(factory).getRepository(LowStockAlertSkuRepository.class);
    }

    @Bean
    NotificationRepository lowStockNotifications(EntityManagerFactory factory) {
        return repositories(factory).getRepository(NotificationRepository.class);
    }

    @Bean
    SnowflakeIdGenerator lowStockIds() {
        return new SnowflakeIdGenerator(3, 9);
    }

    @Bean
    SampleClock lowStockClock() {
        return new SampleClock();
    }

    @Bean(name = "lowStockAlertTicker", defaultCandidate = false)
    SampleTicker lowStockAlertTicker() {
        return new SampleTicker();
    }

    static final class SampleTicker implements LongSupplier {
        volatile long now;

        @Override
        public long getAsLong() {
            return now;
        }
    }

    private static JpaRepositoryFactory repositories(EntityManagerFactory factory) {
        return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory));
    }

    static final class SampleClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-28T01:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
