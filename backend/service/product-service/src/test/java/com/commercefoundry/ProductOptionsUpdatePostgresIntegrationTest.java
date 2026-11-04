package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Production engine and schema execute the inherited CAS and transaction contracts. */
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
class ProductOptionsUpdatePostgresIntegrationTest extends ProductOptionsUpdateIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @Autowired DataSource dataSource;

    @Test
    void generatedUpgradePreservesLegacyRowsAndInitializesTranslationsToNull() throws Exception {
        // PostgreSQL transactional DDL isolates this legacy upgrade exercise from the application schema and inherited tests.
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var groupsBefore = legacyRows(connection, "product_option_group");
                var valuesBefore = legacyRows(connection, "product_option_value");
                try (var statement = connection.createStatement()) {
                    statement.execute("alter table product.product_option_group drop column name_en");
                    statement.execute("alter table product.product_option_value drop column name_en");
                }
                ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource("ddl/option-localization-upgrade.sql"), java.nio.charset.StandardCharsets.UTF_8));
                assertThat(legacyRows(connection, "product_option_group")).isEqualTo(groupsBefore);
                assertThat(legacyRows(connection, "product_option_value")).isEqualTo(valuesBefore);
                for (String table : List.of("product_option_group", "product_option_value")) {
                    try (var statement = connection.createStatement();
                         var rows = statement.executeQuery("select name_en from product." + table)) {
                        int count = 0;
                        while (rows.next()) {
                            assertThat(rows.getString(1)).isNull();
                            count++;
                        }
                        assertThat(count).isPositive();
                    }
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static List<List<Object>> legacyRows(Connection connection, String table) throws SQLException {
        var result = new ArrayList<List<Object>>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("select * from product." + table + " order by id")) {
            var metadata = rows.getMetaData();
            while (rows.next()) {
                var row = new ArrayList<Object>();
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    if (!metadata.getColumnName(column).equals("name_en")) row.add(rows.getObject(column));
                }
                result.add(row);
            }
        }
        return result;
    }
}
