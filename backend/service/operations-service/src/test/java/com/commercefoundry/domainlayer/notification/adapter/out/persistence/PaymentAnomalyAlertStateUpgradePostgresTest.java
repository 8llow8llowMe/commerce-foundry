package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicy;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 운영 업그레이드 스크립트({@code ddl/payment-anomaly-alert-state-auto-cancel.sql})를 #477 PR 2 이전 다섯 사건 테이블에 적용한다.
 * 기대 최종 모양(CHECK 정의·테이블 주석)은 같은 컨테이너에 적용한 현재 생성 스냅숏에서 읽는다 — 업그레이드한 DB 와 새로 만든 DB 가 같아야 한다.
 */
@Testcontainers
@EnabledWithDockerOrOnCi
class PaymentAnomalyAlertStateUpgradePostgresTest {
    private static final String SCRIPT = "ddl/payment-anomaly-alert-state-auto-cancel.sql";
    private static final String TABLE = "operations.payment_anomaly_alert_state";
    private static final String NEW_FAMILY = PaymentAnomalyPolicy.Family.AUTO_CANCEL_MANUAL_REVIEW.name();
    private static final List<String> SIX_FAMILIES = Arrays.stream(PaymentAnomalyPolicy.Family.values()).map(Enum::name).sorted().toList();
    private static final List<String> LEGACY_FAMILIES = SIX_FAMILIES.stream().filter(family -> !family.equals(NEW_FAMILY)).toList();
    private static final Instant OBSERVED = Instant.parse("2026-11-20T01:02:03.123456Z");
    private static final Instant ACCEPTED = OBSERVED.plusSeconds(5);

    /** #477 PR 2 이전 생성 스냅숏의 테이블 그대로다(다섯 사건 family·severity CHECK). */
    private static final String LEGACY_TABLE = """
        create table operations.payment_anomaly_alert_state (
            family varchar(32) not null,
            created_at timestamp(6) with time zone not null,
            updated_at timestamp(6) with time zone not null,
            first_detected_at timestamp(6) with time zone,
            last_accepted_local_at timestamp(6) with time zone not null,
            last_alert_at timestamp(6) with time zone,
            last_observed_at timestamp(6) with time zone not null,
            severity varchar(16) check ((severity in ('INFO','WARNING','CRITICAL'))),
            status varchar(16) not null check ((status in ('NORMAL','ACTIVE'))),
            version bigint not null,
            primary key (family),
            constraint ck_payment_anomaly_alert_state_family check (family in ('UNKNOWN','IN_PROGRESS','RETURNED_READY','LATE_APPROVED',
                'DONE_ORDER_UNSYNCED')),
            constraint ck_payment_anomaly_alert_state_incident check ((status = 'NORMAL' and severity is null and first_detected_at is null
                and last_alert_at is null) or (status = 'ACTIVE' and severity is not null and severity in ('WARNING','CRITICAL')
                and first_detected_at is not null and last_alert_at is not null)),
            constraint ck_payment_anomaly_alert_state_severity check (status = 'NORMAL' or family = 'UNKNOWN'
                or (family in ('IN_PROGRESS','RETURNED_READY') and severity = 'WARNING')
                or (family in ('LATE_APPROVED','DONE_ORDER_UNSYNCED') and severity = 'CRITICAL')),
            constraint ck_payment_anomaly_alert_state_chronology check (last_observed_at <= last_accepted_local_at and (status = 'NORMAL'
                or (first_detected_at <= last_observed_at and first_detected_at <= last_alert_at and last_alert_at <= last_accepted_local_at)))
        )""";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    private static Map<String, String> expectedChecks;
    private static String expectedComment;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void readGeneratedShape() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        expectedChecks = checks();
        expectedComment = tableComment();
        assertThat(expectedChecks.get("ck_payment_anomaly_alert_state_family")).contains("'" + NEW_FAMILY + "'");
        assertThat(expectedComment).contains("여섯 사건");
    }

    @BeforeEach
    void legacyTable() {
        jdbc.execute("drop table " + TABLE);
        jdbc.execute(LEGACY_TABLE);
        jdbc.execute("comment on table " + TABLE + " is '결제 이상 다섯 사건의 수락 워터마크'");
        assertThat(checks()).isNotEqualTo(expectedChecks);
    }

    @Test
    void fiveLegacyRowsBecomeSixWithCopiedWatermarksMatchingFreshSchemaAndRerunChangesNothing() throws Exception {
        seedLegacyRows(LEGACY_FAMILIES);
        // 스크립트 전에는 새 사건 행을 쓸 수 없다 — 새 애플리케이션보다 DDL 이 먼저인 이유
        assertThatThrownBy(() -> insertNormal(NEW_FAMILY)).isInstanceOf(DataIntegrityViolationException.class);
        var legacy = rows();

        migrate();

        var upgraded = rows();
        assertThat(upgraded).extracting(row -> row.get("family")).containsExactlyElementsOf(SIX_FAMILIES);
        assertThat(upgraded.stream().filter(row -> !NEW_FAMILY.equals(row.get("family"))).toList()).isEqualTo(legacy);
        var added = upgraded.stream().filter(row -> NEW_FAMILY.equals(row.get("family"))).findFirst().orElseThrow();
        assertThat(added.get("status")).isEqualTo("NORMAL");
        assertThat(added.get("severity")).isNull();
        assertThat(added.get("first_detected_at")).isNull();
        assertThat(added.get("last_alert_at")).isNull();
        assertThat(added.get("version")).isEqualTo(0L);
        assertThat(added.get("created_at")).isNotNull();
        assertThat(added.get("updated_at")).isNotNull();
        // 공통 원천/로컬 워터마크는 모든 행이 같아야 저장 어댑터가 손상으로 보지 않는다
        assertThat(jdbc.queryForObject("select count(distinct (last_observed_at, last_accepted_local_at)) from " + TABLE, Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select last_observed_at from " + TABLE + " where family = ?", Timestamp.class, NEW_FAMILY).toInstant())
            .isEqualTo(OBSERVED);
        assertThat(jdbc.queryForObject("select last_accepted_local_at from " + TABLE + " where family = ?", Timestamp.class, NEW_FAMILY).toInstant())
            .isEqualTo(ACCEPTED);
        assertThat(checks()).isEqualTo(expectedChecks);
        assertThat(tableComment()).isEqualTo(expectedComment);

        // 새 CHECK: 자동 취소 수동 확인은 CRITICAL 로만 활성화된다
        String activate = "update " + TABLE + " set status = 'ACTIVE', severity = ?, first_detected_at = last_observed_at, "
            + "last_alert_at = last_accepted_local_at where family = ?";
        assertThatThrownBy(() -> jdbc.update(activate, "WARNING", NEW_FAMILY)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(activate, "CRITICAL", NEW_FAMILY)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update " + TABLE + " set family = 'OTHER' where family = ?", NEW_FAMILY))
            .isInstanceOf(DataIntegrityViolationException.class);
        var active = rows();

        // 재실행 멱등 — 여섯 행이면 넣지 않고, 활성 상태도 건드리지 않는다
        migrate();
        assertThat(rows()).isEqualTo(active);
        assertThat(checks()).isEqualTo(expectedChecks);
    }

    @Test
    void emptyTableGetsNewChecksButNoRowsSoFirstCollectionInsertsAllSix() throws Exception {
        migrate();
        assertThat(rows()).isEmpty();
        assertThat(checks()).isEqualTo(expectedChecks);
        assertThat(tableComment()).isEqualTo(expectedComment);
        migrate();
        assertThat(rows()).isEmpty();
        for (String family : SIX_FAMILIES) {
            insertNormal(family);
        }
        assertThat(rows()).extracting(row -> row.get("family")).containsExactlyElementsOf(SIX_FAMILIES);
    }

    @Test
    void partialRowsAreRefusedBeforeAnyChange() throws Exception {
        seedLegacyRows(LEGACY_FAMILIES.subList(0, 3));
        var before = rows();
        var legacyChecks = checks();
        assertThatThrownBy(this::migrate).isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("P0001"));
        assertThat(rows()).isEqualTo(before);
        assertThat(checks()).isEqualTo(legacyChecks);
        assertThat(tableComment()).contains("다섯 사건");
    }

    @Test
    void missingNamedCheckIsRefusedBeforeAnyChange() throws Exception {
        seedLegacyRows(LEGACY_FAMILIES);
        jdbc.execute("alter table " + TABLE + " rename constraint ck_payment_anomaly_alert_state_severity to ck_payment_anomaly_alert_state_severity_old");
        var before = rows();
        var legacyChecks = checks();
        assertThatThrownBy(this::migrate).isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("P0001"));
        assertThat(rows()).isEqualTo(before);
        assertThat(checks()).isEqualTo(legacyChecks);
    }

    private void seedLegacyRows(List<String> families) {
        for (String family : families) {
            boolean active = family.equals("UNKNOWN") || family.equals("LATE_APPROVED");
            jdbc.update("insert into " + TABLE + " (family, created_at, updated_at, first_detected_at, last_accepted_local_at, last_alert_at, "
                    + "last_observed_at, severity, status, version) values (?, now(), now(), ?, ?, ?, ?, ?, ?, 7)",
                family, active ? Timestamp.from(OBSERVED.minusSeconds(60)) : null, Timestamp.from(ACCEPTED),
                active ? Timestamp.from(ACCEPTED) : null, Timestamp.from(OBSERVED), active ? "CRITICAL" : null, active ? "ACTIVE" : "NORMAL");
        }
    }

    private void insertNormal(String family) {
        jdbc.update("insert into " + TABLE + " (family, created_at, updated_at, last_accepted_local_at, last_observed_at, status, version) "
            + "values (?, now(), now(), ?, ?, 'NORMAL', 0)", family, Timestamp.from(ACCEPTED), Timestamp.from(OBSERVED));
    }

    private static List<Map<String, Object>> rows() {
        return jdbc.queryForList("select * from " + TABLE + " order by family");
    }

    private static Map<String, String> checks() {
        var result = new LinkedHashMap<String, String>();
        jdbc.query("select conname, pg_get_constraintdef(oid) as definition from pg_constraint where conrelid = '" + TABLE
            + "'::regclass and contype = 'c' order by conname", row -> {
                result.put(row.getString("conname"), row.getString("definition"));
            });
        return result;
    }

    private static String tableComment() {
        return jdbc.queryForObject("select obj_description('" + TABLE + "'::regclass, 'pg_class')", String.class);
    }

    private void migrate() throws Exception {
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream(SCRIPT)) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 운영 스크립트의 BEGIN/COMMIT 이 한 물리 커넥션에서 돌아야 한다.
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try {
                statement.execute(sql);
            } catch (SQLException failure) {
                statement.execute("rollback");
                throw failure;
            }
        }
    }
}
