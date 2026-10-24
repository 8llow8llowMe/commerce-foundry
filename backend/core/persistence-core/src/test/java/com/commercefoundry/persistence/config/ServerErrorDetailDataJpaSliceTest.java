package com.commercefoundry.persistence.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code @DataJpaTest} 슬라이스는 {@code AutoConfiguration.imports} 를 읽지 않는다. {@code AutoConfigureDataJpa.imports} 에도 등록해
 * 서비스의 리포지토리 테스트(실제 PostgreSQL — 제약 위반 로그에 행 값이 없음을 보는 테스트 포함)가 운영과 같은 드라이버 설정으로 돈다 (#279).
 * 서비스 테스트처럼 테스트 DB 로 바꾸지 않고({@code Replace.NONE}) Hikari 풀을 그대로 쓴다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:server-error-detail-slice")
@ContextConfiguration(classes = ServerErrorDetailDataJpaSliceTest.SliceApplication.class)
class ServerErrorDetailDataJpaSliceTest {

    @Autowired
    private HikariDataSource dataSource;

    @Test
    @DisplayName("@DataJpaTest 슬라이스의 커넥션 풀도 드라이버에 logServerErrorDetail=false 를 넘긴다")
    void appliesInDataJpaSlice() {
        assertThat(dataSource.getDataSourceProperties()).containsEntry(ServerErrorDetailAutoConfiguration.LOG_SERVER_ERROR_DETAIL, "false");
    }

    /** JPA 리포지토리 자동 구성이 기준 패키지를 찾는다 — 엔티티 · 리포지토리는 없다. */
    @SpringBootConfiguration
    @AutoConfigurationPackage
    static class SliceApplication {
    }
}
