package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.adapter.out.log.AdminAuditLogAdapter;
import com.commercefoundry.domainlayer.member.application.mapper.AdminAuditLogMapperImpl;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditLogRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditPort;
import com.commercefoundry.domainlayer.member.domain.model.AdminAuditLog;
import com.commercefoundry.global.config.AuthServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 감사 로그 테이블 — PostgreSQL 컨테이너에 DDL 스냅숏을 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한 뒤 실제로 넣고 읽는다.
 * 어댑터가 REQUIRES_NEW 로 커밋하므로 테스트 트랜잭션을 끈다 — 행이 컨테이너에 남아 관리자 아이디로 걸러 본다.
 * Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=auth",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({
    AuthServiceFeaturesConfig.class,
    AdminAuditLogAdapter.class,
    AdminAuditLogMapperImpl.class,
    AdminAuditLogRepositoryAdapter.class,
    QuerydslConfigurer.class,
    AdminAuditLogPersistenceAdapterTest.Ids.class
})
class AdminAuditLogPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/auth-schema.sql");

    @Autowired
    private AdminAuditPort adminAuditPort;

    @Autowired
    private AdminAuditLogRepositoryPort adminAuditLogRepositoryPort;

    @TestConfiguration
    static class Ids {

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(1, 1);
        }
    }

    @Test
    @DisplayName("행위마다 한 행(행위자 · 대상이 없는 로그인 실패는 행이 없다) — 시각이 채워지고, 조건마다 아이디 내림차순 · 전체 건수는 같은 조건이다")
    void insertsAndFinds() {
        // 이 클래스는 테스트가 하나이고 컨테이너도 이 클래스 것이라 행은 아래 셋뿐이다
        long admin = System.nanoTime();
        adminAuditPort.adminLoginSucceeded(admin);
        adminAuditPort.membersSearched(admin, MemberRole.ADMIN, 1, 50);
        adminAuditPort.adminLoginFailed(OptionalLong.empty());
        adminAuditPort.adminLoginFailed(OptionalLong.of(admin));

        Page<AdminAuditLog> all = adminAuditLogRepositoryPort.findPage(null, null, null, null, 0, 10);
        assertThat(all.getContent()).extracting(AdminAuditLog::action).containsExactly("ADMIN_LOGIN", "MEMBER_SEARCH", "ADMIN_LOGIN");
        assertThat(all.getContent().getFirst()).satisfies(failed -> {
            assertThat(failed.actorMemberId()).isNull();
            assertThat(failed.target()).isEqualTo("member:" + admin);
            assertThat(failed.result()).isEqualTo("FAILURE");
        });
        assertThat(all.getContent()).allSatisfy(log -> assertThat(log.createdAt()).isNotNull());

        // page 1 · size 1 이라 count 가 반드시 돈다 — 전체 건수가 내용과 같은 조건인지 본다
        Page<AdminAuditLog> byActor = adminAuditLogRepositoryPort.findPage(null, admin, null, null, 1, 1);
        assertThat(byActor.getContent()).extracting(AdminAuditLog::action).containsExactly("ADMIN_LOGIN");
        assertThat(byActor.getTotalElements()).isEqualTo(2);

        Page<AdminAuditLog> logins = adminAuditLogRepositoryPort.findPage("ADMIN_LOGIN", null, null, null, 1, 1);
        assertThat(logins.getContent()).singleElement().satisfies(log -> assertThat(log.result()).isEqualTo("SUCCESS"));
        assertThat(logins.getTotalElements()).isEqualTo(2);

        Page<AdminAuditLog> actorLogins = adminAuditLogRepositoryPort.findPage("ADMIN_LOGIN", admin, null, null, 1, 1);
        assertThat(actorLogins.getContent()).isEmpty();
        assertThat(actorLogins.getTotalElements()).isEqualTo(1);

        Page<AdminAuditLog> beyond = adminAuditLogRepositoryPort.findPage(null, null, null, null, 5, 10);

        Page<AdminAuditLog> failedAgainstAdmin = adminAuditLogRepositoryPort.findPage(
            "ADMIN_LOGIN", null, "member:" + admin, "FAILURE", 0, 10
        );
        assertThat(failedAgainstAdmin.getContent()).singleElement().satisfies(log -> assertThat(log.actorMemberId()).isNull());
        assertThat(failedAgainstAdmin.getTotalElements()).isEqualTo(1);
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(3);
    }
}
