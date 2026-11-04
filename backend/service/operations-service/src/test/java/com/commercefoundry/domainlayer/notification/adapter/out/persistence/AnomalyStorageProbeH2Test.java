package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** H2는 소량 기능/rollback 진단만 담당한다. PG timeout/성능의 대체 증거가 아니다. */
@SpringJUnitConfig(AnomalyProbeConfig.class)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:anomaly-storage-probe;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.jpa.properties.hibernate.generate_statistics=true", "spring.jpa.properties.hibernate.use_sql_comments=false",
    "spring.data.jpa.repositories.enabled=false"
})
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnomalyStorageProbeH2Test extends AnomalyProbeSupport {
    @Test
    void bothCandidatesCommitIdenticalSmallSyntheticShapes() {
        for (var mode : List.of(AnomalyProbeStore.Mode.A_SAVE_ALL, AnomalyProbeStore.Mode.B_PERSIST)) {
            committed(mode, 3, 1);
            jdbc.update("delete from operations.notification");
        }
    }

    @Test
    void validateWholeInputBeforeWritingAndRequireCallerTransaction() {
        var fresh = AnomalyProbeStore.fixture(1).getFirst();
        for (var mode : List.of(AnomalyProbeStore.Mode.A_SAVE_ALL, AnomalyProbeStore.Mode.B_PERSIST)) {
            assertThatThrownBy(() -> store().insert(mode, List.of(fresh))).isInstanceOf(IllegalStateException.class);
            transaction().executeWithoutResult(status -> {
                assertThatThrownBy(() -> store().insert(mode, List.of())).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> store().insert(mode, null)).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> store().insert(mode, AnomalyProbeStore.fixture(5001))).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> store().insert(mode, List.of(fresh, fresh))).isInstanceOf(IllegalArgumentException.class);
                var nullable = new ArrayList<Notification>();
                nullable.add(fresh);
                nullable.add(null);
                assertThatThrownBy(() -> store().insert(mode, nullable)).isInstanceOf(IllegalArgumentException.class);
                for (var invalid : List.of(fresh.markRead(Instant.now()), copy(fresh, Instant.now(), null), copy(fresh, null, 0L))) {
                    assertThatThrownBy(() -> store().insert(mode, List.of(fresh, invalid))).isInstanceOf(IllegalArgumentException.class);
                }
                assertThat(repository.count()).isZero();
            });
        }
    }

    @Test
    void smallPostFlushFailureRollsBackBothCandidates() {
        for (var mode : List.of(AnomalyProbeStore.Mode.A_SAVE_ALL, AnomalyProbeStore.Mode.B_PERSIST)) {
            var input = AnomalyProbeStore.fixture(3);
            var result = measure(mode + "_EXPECTED_POST_FLUSH_FAILURE", 3, 1, () -> {
                store().insert(mode, input);
                throw new IllegalStateException("synthetic post-flush failure");
            });
            assertThat(result.failure()).isInstanceOf(IllegalStateException.class);
            assertThat(AnomalyProbeMeter.value(result.explicit(), "insertRows")).isEqualTo(3);
            assertThat(jdbc.queryForObject("select count(*) from operations.notification", Long.class)).isZero();
        }
    }

    @Test
    void counterExcludesOtherThreadsAndRecognizesNonAllowlistedSql() throws Exception {
        try (var window = meter.start(entityManagerFactory.unwrap(org.hibernate.SessionFactory.class))) {
            var other = new Thread(() -> jdbc.queryForObject("select count(*) from operations.notification", Long.class));
            other.start();
            other.join(5000);
            assertThat(other.isAlive()).isFalse();
            assertThat(AnomalyProbeMeter.value(window.snapshot(), "selectRows")).isZero();
            jdbc.queryForObject("select 1", Integer.class);
            assertThat(AnomalyProbeMeter.value(window.snapshot(), "otherRows")).isEqualTo(1);
        }
    }

    private static Notification copy(Notification n, Instant created, Long version) {
        return new Notification(n.id(), n.recipientMemberId(), n.kind(), n.severity(), n.title(), n.linkPath(), n.sourceType(), n.sourceId(), null, created, version);
    }
}
