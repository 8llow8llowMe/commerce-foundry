package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;

/** DO 블록의 내부 세미콜론을 분리하지 않고 운영 SQL 전체를 한 연결에서 실행한다. */
final class RestorationSchemaMigration {
    private RestorationSchemaMigration() { }

    static void run(DataSource source) {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute(new ClassPathResource("ddl/order-stock-restoration.sql").getContentAsString(StandardCharsets.UTF_8));
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException | IOException failure) {
            throw new IllegalStateException("restoration schema migration failed", failure);
        }
    }
}
