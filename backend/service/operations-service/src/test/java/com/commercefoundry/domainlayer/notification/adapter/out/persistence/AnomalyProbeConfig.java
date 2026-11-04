package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/** JPA slice 전용: 스케줄러/Feign/Kafka 및 애플리케이션 component scan 없음. */
@TestConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class, TransactionAutoConfiguration.class})
@EnableJpaAuditing
@EntityScan(basePackageClasses = NotificationEntity.class)
class AnomalyProbeConfig {
    @Bean
    NotificationRepository probeRepository(EntityManagerFactory factory) {
        return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory)).getRepository(NotificationRepository.class);
    }

    @Bean
    AnomalyProbeMeter probeMeter() {
        return new AnomalyProbeMeter();
    }

    @Bean
    static BeanPostProcessor probeDataSource(ObjectProvider<AnomalyProbeMeter> meters) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource source && !(bean instanceof ProxyDataSource)) {
                    var meter = meters.getObject();
                    return ProxyDataSourceBuilder.create(source).name("anomaly-storage-probe").listener(meter).methodListener(meter).build();
                }
                return bean;
            }
        };
    }
}
