package com.commercefoundry.global.config;

import com.commercefoundry.global.properties.PaymentGateProperties;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * 결제 게이트가 열려 있으면 outbox 테이블({@code payment.outbox} · {@code payment.shedlock})이 있는지 기동 때 본다 (#308, {@code payment-guide.md} §6).
 *
 * <p>결제 승인(DONE) 기록은 같은 트랜잭션에서 outbox 에 쓴다 — 운영은 {@code ddl-auto=none} 이라 DDL 을 빠뜨리면 PG 가 승인한 뒤 매 결제가 500 {@code PAYMENT_013}
 * (IN_PROGRESS 로 남고 주문은 결제 대기 → 만료)이 된다. 결제를 받기 전에 기동을 멈춰 드러낸다. 게이트가 닫혀 있으면 승인이 없으므로 WARN 만 남긴다.
 *
 * <p>모든 싱글턴(JPA 포함)이 만들어진 뒤에 본다 — local · dev 는 {@code ddl-auto=update} 가 테이블을 먼저 만든다. 확인은 행을 읽지 않는 {@code where 1 = 0} 질의다.
 * 메시지에 테이블 이름만 싣고 DB 오류 원문은 싣지 않는다.
 */
@Slf4j
public class PaymentOutboxSchemaGuard implements SmartInitializingSingleton {

    static final List<String> REQUIRED_TABLES = List.of("payment.outbox", "payment.shedlock");

    private final DataSource dataSource;
    private final PaymentGateProperties gate;
    private final boolean recoveryEnabled;

    public PaymentOutboxSchemaGuard(DataSource dataSource, PaymentGateProperties gate) {
        this(dataSource, gate, false);
    }

    public PaymentOutboxSchemaGuard(DataSource dataSource, PaymentGateProperties gate, boolean recoveryEnabled) {
        this.dataSource = dataSource;
        this.gate = gate;
        this.recoveryEnabled = recoveryEnabled;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> missing = missingTables();
        if (missing.isEmpty()) {
            return;
        }
        if (gate.enabled() || recoveryEnabled) {
            throw new IllegalStateException("결제 또는 복구 게이트(payment.enabled / payment.recovery.enabled)가 열려 있는데 outbox 테이블이 없습니다: " + String.join(", ", missing)
                + " — 운영 DDL(payment-schema.sql 의 outbox · shedlock)을 먼저 적용하세요 (deploy-guide.md §4-2)");
        }
        log.warn("payment outbox tables are missing. payment gate is closed, so approvals are not recorded. tables={}", missing);
    }

    private List<String> missingTables() {
        List<String> missing = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (String table : REQUIRED_TABLES) {
                if (!exists(connection, table)) {
                    missing.add(table);
                }
            }
        } catch (SQLException exception) {
            // 연결 자체가 안 되면 여기서 판정하지 않는다 — DataSource · JPA 가 이미 기동을 멈췄거나 따로 드러난다
            throw new IllegalStateException("outbox 테이블을 확인하지 못했습니다. cause=" + exception.getClass().getSimpleName());
        }
        return missing;
    }

    private static boolean exists(Connection connection, String table) throws SQLException {
        // 테이블 이름은 위 상수뿐이다 — 외부 입력이 들어오지 않는다
        try (Statement statement = connection.createStatement()) {
            statement.execute("select 1 from " + table + " where 1 = 0");
            return true;
        } catch (SQLException exception) {
            if (!connection.getAutoCommit()) {
                // PostgreSQL 은 실패한 문장 뒤 트랜잭션을 중단한다 — 다음 테이블 확인이 같은 이유로 실패하지 않게 되돌린다
                connection.rollback();
            }
            return false;
        }
    }
}
