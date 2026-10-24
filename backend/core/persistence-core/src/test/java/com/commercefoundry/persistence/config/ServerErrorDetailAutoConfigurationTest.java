package com.commercefoundry.persistence.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 커넥션 풀은 만들기만 하고 열지 않는다 — Hikari 는 첫 커넥션 요청 때 드라이버를 붙이므로 DB 없이 속성만 본다.
 */
class ServerErrorDetailAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ServerErrorDetailAutoConfiguration.class));

    @Test
    @DisplayName("서비스가 아무것도 두지 않아도 커넥션 풀이 드라이버에 logServerErrorDetail=false 를 넘긴다")
    void appliesWithoutServiceSetting() {
        contextRunner.withBean(HikariDataSource.class, HikariDataSource::new)
            .run(context -> assertThat(context.getBean(HikariDataSource.class).getDataSourceProperties())
                .containsEntry(ServerErrorDetailAutoConfiguration.LOG_SERVER_ERROR_DETAIL, "false"));
    }

    @Test
    @DisplayName("설정이 true 로 두어도 false 로 덮는다")
    void overridesTrue() {
        contextRunner.withBean(HikariDataSource.class, () -> {
                HikariDataSource dataSource = new HikariDataSource();
                dataSource.addDataSourceProperty(ServerErrorDetailAutoConfiguration.LOG_SERVER_ERROR_DETAIL, "true");
                return dataSource;
            })
            .run(context -> assertThat(context.getBean(HikariDataSource.class).getDataSourceProperties())
                .containsEntry(ServerErrorDetailAutoConfiguration.LOG_SERVER_ERROR_DETAIL, "false"));
    }

    @Test
    @DisplayName("자동 구성 목록(AutoConfiguration.imports)에 등록돼 있다 — 빠지면 서비스에 걸리지 않는다")
    void registeredAsAutoConfiguration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
            .contains(ServerErrorDetailAutoConfiguration.class.getName());
    }
}
