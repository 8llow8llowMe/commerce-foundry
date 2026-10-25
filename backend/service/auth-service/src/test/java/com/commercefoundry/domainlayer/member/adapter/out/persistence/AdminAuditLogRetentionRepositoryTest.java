package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminAuditLogEntity;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.AdminAuditLogRepository;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.AdminAuditLogRetentionRepository;
import com.commercefoundry.global.config.AuthServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 보관 정리 — PostgreSQL 에 스냅숏을 적용하고, 컷오프 아이디보다 작은 행만 배치로 지워지는지 본다 (#286).
 * 판정은 {@code created_at} 이 아니라 Snowflake 아이디다. 컷오프 시각의 행과 그 이후 행은 남는다.
 * Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=auth",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({AuthServiceFeaturesConfig.class, QuerydslConfigurer.class})
class AdminAuditLogRetentionRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/auth-schema.sql");

    @Autowired
    private AdminAuditLogRetentionRepository adminAuditLogRetentionRepository;

    @Autowired
    private AdminAuditLogRepository adminAuditLogRepository;

    @Test
    @DisplayName("컷오프보다 이전 행만 배치로 지워지고, 컷오프 시각과 그 이후 행은 남는다")
    void deletesOnlyRowsOlderThanTheCutoff() {
        long cutoff = SnowflakeIdGenerator.lowestIdAt(Instant.parse("2027-01-01T00:00:00Z"));
        long expired = SnowflakeIdGenerator.lowestIdAt(Instant.parse("2026-03-01T00:00:00Z"));
        long justBefore = cutoff - 1;
        long boundary = cutoff;
        long recent = SnowflakeIdGenerator.lowestIdAt(Instant.parse("2028-01-01T00:00:00Z"));
        save(expired);
        save(justBefore);
        save(boundary);
        save(recent);

        List<Long> firstBatch = adminAuditLogRetentionRepository.findIdsOlderThan(cutoff, PageRequest.of(0, 1));
        assertThat(firstBatch).containsExactly(expired);
        assertThat(adminAuditLogRetentionRepository.deleteByIds(firstBatch)).isEqualTo(1);

        List<Long> rest = adminAuditLogRetentionRepository.findIdsOlderThan(cutoff, PageRequest.of(0, 10));
        assertThat(rest).containsExactly(justBefore);
        adminAuditLogRetentionRepository.deleteByIds(rest);

        assertThat(adminAuditLogRetentionRepository.findIdsOlderThan(cutoff, PageRequest.of(0, 10))).isEmpty();
        assertThat(adminAuditLogRetentionRepository.findIdsOlderThan(Long.MAX_VALUE, PageRequest.of(0, 10))).containsExactly(boundary, recent);
    }

    /** {@code createdAt} 을 비워 새 행으로 넣는다. 삭제 판정은 아이디라 감사 시각은 지금이어도 된다. */
    private void save(long id) {
        adminAuditLogRepository.saveAndFlush(AdminAuditLogEntity.builder()
            .id(id)
            .action("ADMIN_LOGIN")
            .target("member:1")
            .result("FAILURE")
            .build());
    }
}
