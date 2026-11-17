package com.commercefoundry.domainlayer.productdraftgeneration.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 같은 등록 · 회차 · 나노초 재생 · 동시 등록 검증을 PostgreSQL timestamp(6) · 복합 PK · 회차 CHECK 위에서 다시 돌린다.
 * 스키마는 손 관리 스냅숏(init script)이고 Hibernate 는 {@code validate} 만 한다 — {@code @TestPropertySource} 가 상위의 {@code create-drop} 보다 우선한다.
 */
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class ProductDraftGenerationRegistrationPostgresTest extends ProductDraftGenerationRegistrationH2Test {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
        .withInitScript("ddl/operations-schema.sql");

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void databaseCheckRejectsGenerationOutsideOneToThree() {
        var jdbc = new JdbcTemplate(dataSource);
        assertThatThrownBy(() -> jdbc.update("insert into operations.product_draft_generation_receipt "
            + "(draft_id, generation_no, expires_at, input_hash, job_id, requested_by_member_id) values (?, ?, now(), ?, ?, ?)",
            10L, 4, "a".repeat(64), 99L, 7L)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
