package com.commercefoundry.domainlayer.settlement.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercefoundry.domainlayer.settlement.application.exception.SettlementErrorCode;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementException;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDay;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDetection;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatch;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatchType;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL 17 — 스냅숏 DDL 을 init script 로 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한다. 유니크 키 위반의 409 변환과 NOWAIT 잠금은 여기서만 본다.
 * Docker 없는 로컬은 건너뛰고 CI 에서는 실패한다 ({@link EnabledWithDockerOrOnCi}). 제약 위반은 시험 하나에 하나만 낸다 — PostgreSQL 은 첫 오류 뒤 트랜잭션을 중단한다.
 */
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=payment"})
class SettlementPersistencePostgresTest extends SettlementPersistenceContract {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");

    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("같은 매출일을 다른 실행이 먼저 만들었으면(매출일 유니크 키) 409 SETTLEMENT_002")
    void soldDateUniqueKeyIsConflict() {
        dayPort.save(SettlementDay.collected(41L, SOLD_DATE, summary(0, 0), NOW));
        em.flush();

        assertThatThrownBy(() -> dayPort.save(SettlementDay.collected(42L, SOLD_DATE, summary(0, 0), NOW))).isInstanceOfSatisfying(SettlementException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(SettlementErrorCode.RECONCILE_CONFLICT));
    }

    @Test
    @DisplayName("다른 날짜의 대사가 같은 (결제키, 종류)를 먼저 넣었으면 409 SETTLEMENT_002 — 행이 둘이 되지 않는다")
    void paymentKeyTypeUniqueKeyIsConflict() {
        SettlementDetection detection = new SettlementDetection("tgen_contract_0001", 9_000_000_001L, SettlementMismatchType.AMOUNT, 129_000, 100_000);
        mismatchPort.saveAll(List.of(SettlementMismatch.open(51L, SOLD_DATE, detection, CurrencyCode.KRW, NOW)));
        em.flush();

        assertThatThrownBy(() -> mismatchPort.saveAll(List.of(SettlementMismatch.open(52L, SOLD_DATE.plusDays(1), detection, CurrencyCode.KRW, NOW))))
            .isInstanceOfSatisfying(SettlementException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(SettlementErrorCode.RECONCILE_CONFLICT));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("같은 매출일 행을 다른 실행이 잡고 있으면 기다리지 않고(NOWAIT) 바로 409 SETTLEMENT_002")
    void lockIsNowait() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> dayPort.save(SettlementDay.collected(61L, SOLD_DATE, summary(0, 0), NOW)));
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.executeQuery("select id from payment.settlement_day where sold_date = date '2026-11-20' for update").close();
                Instant started = Instant.now();
                Throwable thrown = catchThrowable(() -> transaction.executeWithoutResult(status -> dayPort.lockBySoldDate(SOLD_DATE)));

                assertThat(thrown).isInstanceOfSatisfying(SettlementException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(SettlementErrorCode.RECONCILE_CONFLICT));
                assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(5));
            } finally {
                blocker.rollback();
            }
        } finally {
            transaction.executeWithoutResult(status -> em.getEntityManager().createQuery("delete from SettlementDayEntity").executeUpdate());
        }
    }
}
