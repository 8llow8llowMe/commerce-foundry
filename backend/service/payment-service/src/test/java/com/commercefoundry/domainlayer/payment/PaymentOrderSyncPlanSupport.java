package com.commercefoundry.domainlayer.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 PG17 합성 데이터와 Hibernate가 생성한 SQL만 측정한다. 생산 스키마/인덱스는 바꾸지 않는다. */
public final class PaymentOrderSyncPlanSupport {
    private final JdbcTemplate jdbc;
    private final PaymentRepository repository;
    private static final Instant CUTOFF = PaymentOrderSyncQueryContract.NOW.minusSeconds(300);

    PaymentOrderSyncPlanSupport(JdbcTemplate jdbc, PaymentRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    void measure() {
        System.out.println("C3a-2 PG version=" + jdbc.queryForObject("show server_version", String.class)
            + " budget=query-timeout-2000ms samples=first/repeat1/repeat2 pendingAfter=300s"
            + " assumed-instances=1 scrape-interval=60s queries-per-scrape=3; no cold-cache claim");
        try {
            for (int size : new int[]{10_000, 100_000}) {
                for (String scenario : List.of("normal", "pending-backlog", "rejected-backlog")) {
                    fixture(size, scenario);
                    jdbc.execute("analyze payment.payment");
                    System.out.println("C3a-2 PG fixture rows=" + size + " scenario=" + scenario + " distribution="
                        + jdbc.queryForList("select status,order_synced,order_sync_failure_code is not null as rejected,count(*) as rows"
                            + " from payment.payment group by status,order_synced,order_sync_failure_code is not null"
                            + " order by status,order_synced,rejected"));
                    for (int repeat = 0; repeat < 3; repeat++) {
                        sample("pending-count", repeat, () -> repository.countAwaitingOrderReply(PaymentOrderSyncQueryContract.HOLDING, CUTOFF),
                            jdbc.queryForObject("select count(*)" + PaymentOrderSyncQueryContract.PENDING_SQL, Long.class, Timestamp.from(CUTOFF)), true);
                        Timestamp oldest = jdbc.queryForObject("select min(approved_at)" + PaymentOrderSyncQueryContract.PENDING_SQL,
                            Timestamp.class, Timestamp.from(CUTOFF));
                        sample("pending-min", repeat,
                            () -> repository.findOldestAwaitingOrderReplyApprovedAt(PaymentOrderSyncQueryContract.HOLDING, CUTOFF),
                            oldest == null ? null : oldest.toInstant(), true);
                        sample("rejected-count", repeat, () -> repository.countOrderRejected(PaymentOrderSyncQueryContract.HOLDING),
                            jdbc.queryForObject("select count(*)" + PaymentOrderSyncQueryContract.REJECTED_SQL, Long.class), false);
                    }
                }
            }
        } finally {
            jdbc.update("delete from payment.payment");
            jdbc.execute("analyze payment.payment");
        }
    }

    private void sample(String name, int repeat, Supplier<?> query, Object expected, boolean pending) {
        Capture.SQL.remove();
        long started = System.nanoTime();
        assertThat(query.get()).isEqualTo(expected);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        String sql = Capture.SQL.get();
        assertThat(sql).isNotNull().contains("payment.payment", "order_synced", "order_sync_failure_code", "status in (?,?)");
        assertThat(sql.chars().filter(character -> character == '?').count()).isEqualTo(pending ? 3 : 2);
        if (pending) assertThat(sql).contains("approved_at<?");
        assertThat(elapsedMs).isLessThan(2_000);
        // 실제 JPQL SQL과 같은 순서·타입의 합성 바인딩이며 민감값은 없다.
        List<String> plan = jdbc.query(connection -> {
            var statement = connection.prepareStatement("explain (analyze, buffers) " + sql);
            statement.setString(1, "DONE");
            statement.setString(2, "PARTIAL_CANCELED");
            if (pending) statement.setTimestamp(3, Timestamp.from(CUTOFF));
            statement.setQueryTimeout(2);
            return statement;
        }, (rows, rowNumber) -> rows.getString(1));
        assertThat(plan).anyMatch(line -> line.contains("actual time="));
        assertThat(plan).anyMatch(line -> line.contains("Buffers:"));
        assertThat(plan).anyMatch(line -> line.contains("Execution Time:"));
        System.out.println("C3a-2 PG query=" + name + " sample=" + (repeat == 0 ? "first" : "repeat" + repeat)
            + " elapsedMs=" + elapsedMs + " sql=" + sql + " bindings=[DONE,PARTIAL_CANCELED"
            + (pending ? ",synthetic-cutoff=" + CUTOFF : "") + "]");
        plan.forEach(line -> System.out.println("C3a-2 PG plan " + line));
    }

    private void fixture(int size, String scenario) {
        jdbc.update("delete from payment.payment");
        String status;
        String synced;
        String rejected;
        switch (scenario) {
            case "normal" -> {
                status = "case when g%100<10 then 'CANCELED' when g%100<15 then 'PARTIAL_CANCELED' else 'DONE' end";
                synced = "g%100>=15 and g%100<95 or g%100>=10 and g%100<15";
                rejected = "g%100=99";
            }
            case "pending-backlog" -> {
                status = "case when g%100<20 then 'PARTIAL_CANCELED' when g%100<23 then 'CANCELED' else 'DONE' end";
                synced = "g%100>=23 and g%100<33";
                rejected = "g%100>=98";
            }
            case "rejected-backlog" -> {
                status = "case when g%100<20 then 'PARTIAL_CANCELED' when g%100<23 then 'CANCELED' else 'DONE' end";
                synced = "g%100>=23 and g%100<33";
                rejected = "g%100<20 or g%100>=33 and g%100<98";
            }
            default -> throw new IllegalArgumentException("Unknown synthetic scenario");
        }
        String sql = "insert into payment.payment (id,created_at,updated_at,active_order_id,approval_idempotency_key,approval_started_at,"
            + "approved_at,gateway,member_id,method,order_id,order_name,order_sync_failure_code,order_synced,payment_key,pg_order_id,"
            + "status,total_amount,total_currency,version)"
            + " select g,?, ?,g,'00000000-0000-0000-0000-'||lpad(g::text,12,'0'),?::timestamptz-interval '1 day',"
            + "?::timestamptz-case when g%100=95 then interval '60 seconds' else (g%7200+301)*interval '1 second' end,"
            + "'TOSS',1,'CARD',g,'synthetic',case when " + rejected + " then 'ORDER_CANCELED' else null end,"
            + synced + ",'synthetic-key-'||g,'synthetic-order-'||g," + status + ",1000,'KRW',0 from generate_series(1,?) g";
        Timestamp now = Timestamp.from(PaymentOrderSyncQueryContract.NOW);
        jdbc.update(sql, now, now, now, now, size);
        assertThat(jdbc.queryForObject("select count(*) from payment.payment", Long.class)).isEqualTo(size);
    }

    public static final class Capture implements StatementInspector {
        static final ThreadLocal<String> SQL = new ThreadLocal<>();

        @Override
        public String inspect(String sql) {
            SQL.set(sql);
            return sql;
        }
    }
}
