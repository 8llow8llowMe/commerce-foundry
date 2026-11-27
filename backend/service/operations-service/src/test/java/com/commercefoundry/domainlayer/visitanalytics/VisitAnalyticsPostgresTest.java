package com.commercefoundry.domainlayer.visitanalytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.visitanalytics.application.port.out.VisitAnalyticsStore;
import com.commercefoundry.domainlayer.visitanalytics.application.port.out.VisitAnalyticsStore.DailyViews;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
class VisitAnalyticsPostgresTest {

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    @Autowired private VisitAnalyticsStore adapter;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void concurrentDuplicatesAndDistinctEventsUpdateTheSameCounter() throws Exception {
        var day = LocalDate.of(2026, 11, 27);
        UUID duplicate = UUID.randomUUID();
        var start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = List.of(duplicate, duplicate, UUID.randomUUID(), UUID.randomUUID()).stream()
                .map(id -> pool.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 조회 시작 신호를 받지 못했습니다.");
                    }
                    adapter.recordProductView(id, 42L, day);
                    return null;
                })).toList();
            start.countDown();
            for (var task : tasks) {
                task.get(15, TimeUnit.SECONDS);
            }
        }

        assertThat(adapter.dailyProduct(42L, day, day)).containsExactly(new DailyViews(day, 3L));
        assertThat(jdbc.queryForObject("select count(*) from operations.visit_event_receipt", Long.class)).isEqualTo(3L);
    }
}
