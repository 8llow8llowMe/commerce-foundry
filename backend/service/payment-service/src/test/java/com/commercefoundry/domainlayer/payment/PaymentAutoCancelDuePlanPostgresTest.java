package com.commercefoundry.domainlayer.payment;

import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentAutoCancelRepository;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAutoCancel;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PG17에서 자동 취소 재시도 후보의 시간 필터와 ID 순서가 함께 작동하는지 측정한다. */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=payment",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.commercefoundry.domainlayer.payment.PaymentOrderSyncPlanSupport$Capture"})
class PaymentAutoCancelDuePlanPostgresTest {
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");
    @Autowired PaymentAutoCancelRepository repository;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest @ValueSource(strings = {"sparse-tail", "all-due", "leased-tail", "mixed-state"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void hundredThousandDueCandidatesKeepBoundedKeysetWork(String scenario) throws Exception {
        fixture(scenario);
        jdbc.execute("analyze payment.payment_auto_cancel");
        try {
            long start = scenario.equals("all-due") ? 1 : 99_981;
            for (int repeat = 0; repeat < 2; repeat++) {
                var first = measure(scenario, 0, 100_000, repeat);
                assertThat(first).containsExactlyElementsOf(java.util.stream.LongStream.range(start, start + 10).boxed().toList());
                var second = measure(scenario, first.getLast(), 100_000, repeat);
                assertThat(second).containsExactlyElementsOf(java.util.stream.LongStream.range(start + 10, start + 20).boxed().toList());
                if (start > 1) {
                    assertThat(measure(scenario, 50_000, 100_000, repeat)).containsExactlyElementsOf(first);
                    assertThat(measure(scenario, 0, 99_980, repeat)).isEmpty(); // Nonempty ID range with no executable rows.
                } else {
                    assertThat(measure(scenario, 50_000, 100_000, repeat))
                        .containsExactlyElementsOf(java.util.stream.LongStream.range(50_001, 50_011).boxed().toList());
                }
                assertThat(measure(scenario, 100_000, 100_000, repeat)).isEmpty();
            }
        } finally {
            jdbc.execute("truncate table payment.payment_auto_cancel");
            jdbc.execute("analyze payment.payment_auto_cancel");
        }
    }

    private void fixture(String scenario) {
        jdbc.execute("truncate table payment.payment_auto_cancel");
        String now = "'2026-11-08 00:00:00+00'::timestamptz";
        String dueAt = switch (scenario) {
            case "sparse-tail" -> "case when g>99980 then " + now + "-interval '1 minute' else " + now + "+interval '1 day' end";
            case "all-due" -> now + "-(g*interval '1 second')";
            default -> now + "-interval '1 minute'";
        };
        String state = scenario.equals("mixed-state")
            ? "case when g>99980 then 'PENDING' when g%2=0 then 'SUCCEEDED' else 'MANUAL_REVIEW' end" : "'PENDING'";
        String leaseUntil = scenario.equals("leased-tail")
            ? "case when g<=99980 then " + now + "+interval '1 day' else null end" : "null";
        String leaseToken = scenario.equals("leased-tail") ? "case when g<=99980 then 'held' else null end" : "null";
        String manualReason = scenario.equals("mixed-state")
            ? "case when g<=99980 and g%2=1 then 'AUTO_CANCEL_MANUAL_REVIEW' else null end" : "null";
        String sql = "insert into payment.payment_auto_cancel"
            + " (payment_id,created_at,updated_at,pg_key,state,attempts,next_attempt_at,claim_id,amount,currency,"
            + "first_attempt_at,lease_until,lease_token,manual_reason,version)"
            + " select g,?,?, 'payment-auto-cancel-v1-'||g," + state + ",1," + dueAt + ",g+200000,1000,'KRW',"
            + now + "-interval '1 day'," + leaseUntil + "," + leaseToken + "," + manualReason
            + ",0 from generate_series(1,100000) g";
        jdbc.update(sql, Timestamp.from(NOW), Timestamp.from(NOW));
        assertThat(jdbc.queryForObject("select count(*) from payment.payment_auto_cancel", Long.class)).isEqualTo(100_000);
    }

    private List<Long> measure(String scenario, long afterId, long upperId, int repeat) throws Exception {
        PaymentOrderSyncPlanSupport.Capture.SQL.remove();
        long started = System.nanoTime();
        List<Long> ids = repository.dueIds(PaymentAutoCancel.State.PENDING, NOW, afterId, upperId, PageRequest.of(0, 10));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMs).isLessThan(5_000); // Repository query hint is 2s; leave JVM scheduling headroom.
        String sql = PaymentOrderSyncPlanSupport.Capture.SQL.get();
        assertThat(sql).isNotNull().contains("payment_auto_cancel", "order by");
        List<String> plans = jdbc.query(connection -> {
            var statement = connection.prepareStatement("explain (analyze, buffers, format json) " + sql);
            statement.setString(1, "PENDING");
            statement.setTimestamp(2, Timestamp.from(NOW));
            statement.setTimestamp(3, Timestamp.from(NOW));
            statement.setLong(4, afterId);
            statement.setLong(5, upperId);
            statement.setInt(6, 10);
            statement.setQueryTimeout(2);
            return statement;
        }, (rows, rowNumber) -> rows.getString(1));
        assertThat(plans).hasSize(1);
        JsonNode root = new ObjectMapper().readTree(plans.getFirst()).get(0);
        JsonNode plan = root.path("Plan");
        long scanned = scannedRows(plan);
        long buffers = plan.path("Shared Hit Blocks").asLong() + plan.path("Shared Read Blocks").asLong();
        assertThat(plan.path("Actual Rows").asLong()).isEqualTo(ids.size());
        assertThat(scanned).as("due keyset plan: %s", plans.getFirst()).isLessThan(1_000);
        assertThat(buffers).as("due keyset buffers: %s", plans.getFirst()).isLessThan(2_000);
        System.out.println("#579 PG due=" + scenario + " repeat=" + repeat
            + " elapsedMs=" + elapsedMs + " rows=" + ids.size() + " scanned=" + scanned + " buffers=" + buffers
            + " sql=" + sql + " bindings=[PENDING,synthetic-now,synthetic-now,afterId=" + afterId
            + ",upperId=" + upperId + ",limit=10]");
        System.out.println("#579 PG plan " + plans.getFirst());
        return ids;
    }

    private static long scannedRows(JsonNode node) {
        long rows = 0;
        if (node.path("Node Type").asText().contains("Scan")) {
            rows += (node.path("Actual Rows").asLong() + node.path("Rows Removed by Filter").asLong()
                + node.path("Rows Removed by Index Recheck").asLong()) * node.path("Actual Loops").asLong(1);
        }
        for (JsonNode child : node.path("Plans")) rows += scannedRows(child);
        return rows;
    }
}
