package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.global.properties.PaymentGateProperties;
import com.commercefoundry.global.properties.PaymentRecoveryProperties;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * outbox 테이블 기동 가드 — 결제 게이트가 열려 있으면 {@code payment.outbox} · {@code payment.shedlock} 이 없을 때 기동을 멈춘다. H2(PostgreSQL 모드) 인메모리 DB 로 본다.
 */
class PaymentOutboxSchemaGuardTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(GuardTestConfig.class);

    @Test
    @DisplayName("게이트가 열려 있고 두 테이블이 있으면 기동한다")
    void startsWhenTablesExist() {
        runner.withPropertyValues("payment.enabled=true", "test.tables=payment.outbox,payment.shedlock")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(PaymentOutboxSchemaGuard.class));
    }

    @Test
    @DisplayName("게이트가 열려 있는데 테이블이 없으면 기동을 멈추고 빠진 테이블 이름만 알린다")
    void failsWhenTableMissing() {
        runner.withPropertyValues("payment.enabled=true", "test.tables=payment.outbox")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("없습니다: payment.shedlock —"));
        runner.withPropertyValues("payment.enabled=true", "test.tables=")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("없습니다: payment.outbox, payment.shedlock —"));
    }

    @Test
    @DisplayName("게이트가 닫혀 있으면 테이블이 없어도 기동한다 (WARN 만) — 승인을 받지 않는다")
    void closedGateOnlyWarns() {
        runner.withPropertyValues("payment.enabled=false", "test.tables=")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void recoveryRequiresSchemaEvenWhenNewPaymentGateIsClosed() {
        runner.withPropertyValues("payment.enabled=false", "payment.recovery.enabled=true", "test.tables=")
            .run(context -> assertThat(context).hasFailed().getFailure().isInstanceOf(IllegalStateException.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({PaymentGateProperties.class, PaymentRecoveryProperties.class})
    static class GuardTestConfig {

        /** 테스트마다 새 DB — {@code test.tables} 에 적은 테이블만 만든다. */
        @Bean
        DataSource dataSource(org.springframework.core.env.Environment environment) {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:payment-guard-" + DATABASES.incrementAndGet() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS payment", "sa", "");
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            for (String table : environment.getProperty("test.tables", "").split(",")) {
                if (!table.isBlank()) {
                    jdbc.execute("create table " + table.trim() + " (id bigint)");
                }
            }
            return dataSource;
        }

        @Bean
        PaymentOutboxSchemaGuard paymentOutboxSchemaGuard(DataSource dataSource, PaymentGateProperties gate, PaymentRecoveryProperties recovery) {
            return new PaymentOutboxSchemaGuard(dataSource, gate, recovery.enabled());
        }
    }
}
