package com.commercefoundry.domainlayer.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.PaymentAnomalyObservationAdapter;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAnomalyObservationProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAnomalyObservation;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자동 취소 게이트가 꺼진 운영 DB 에는 payment_auto_cancel 이 없을 수 있다(ddl-auto none, SchemaGuard 는 게이트 ON 에서만).
 * 이 컨텍스트 전용 H2 DB 에서 테이블을 지워, 게이트 OFF 관측이 그 테이블을 전혀 건드리지 않음을 실제 질의로 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, PaymentAnomalyObservationAdapter.class})
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:payment-anomaly-observation-without-auto-cancel;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS payment",
    "spring.jpa.properties.hibernate.default_schema=payment",
    "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true"
})
class PaymentAnomalyObservationWithoutAutoCancelTableH2Test {
    static final Instant NOW = Instant.parse("2026-11-03T01:02:03.123456Z");
    @Autowired PaymentAnomalyObservationAdapter adapter;
    @Autowired DataSource dataSource;

    @BeforeEach
    void dropAutoCancelTable() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("drop table if exists payment.payment_auto_cancel");
        }
    }

    @Test
    void closedGateObservesSixBucketsAndLeavesSeventhUnobservedWithoutTable() {
        var r = processor(false).observeAnomalies();
        assertThat(r.observedAt()).isEqualTo(NOW);
        assertThat(Arrays.asList(r.unknown(), r.manualReviewUnknown(), r.inProgress(), r.returnedReady(), r.lateApproved(), r.doneOrderUnsynced()))
            .allSatisfy(bucket -> assertThat(bucket).isEqualTo(new PaymentAnomalyObservation.Bucket(0, null)));
        assertThat(r.autoCancelManualReview()).isNull();
    }

    @Test
    void openGateWithoutTableFailsWholeObservationAsDataAccessFailure() {
        assertThatThrownBy(() -> processor(true).observeAnomalies()).isInstanceOf(DataAccessException.class);
    }

    private PaymentAnomalyObservationProcessor processor(boolean autoCancelEnabled) {
        var processor = new PaymentAnomalyObservationProcessor(adapter, Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(processor, "autoCancelEnabled", autoCancelEnabled);
        return processor;
    }
}
