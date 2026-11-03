package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import com.commercefoundry.domainlayer.member.application.port.out.AdminNotificationRecipientQueryPort;
import com.commercefoundry.global.config.AuthServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** ID projection 질의를 실제 PostgreSQL17에 실행한다. Docker 없이는 skip, CI에서는 실패한다. */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=auth",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"
})
@Import({AuthServiceFeaturesConfig.class, QuerydslConfigurer.class, AdminNotificationRecipientQueryAdapter.class})
class AdminNotificationRecipientQueryAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/auth-schema.sql");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private AdminNotificationRecipientQueryPort queryPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("활성 ADMIN만 숫자 오름차순이고 afterId는 제외하며 회원 정보·버전을 바꾸지 않는다")
    void filtersAndSortsWithoutChangingAccounts() {
        save(100L, MemberRole.ADMIN, null);
        save(2L, MemberRole.ADMIN, null);
        save(20L, MemberRole.ADMIN, Instant.now());
        save(3L, MemberRole.CUSTOMER, null);
        entityManager.flush();
        entityManager.clear();

        var statistics = entityManager.getEntityManager().getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertThat(queryPort.findActiveAdminIdsAfter(0L, 100)).containsExactly(2L, 100L);
        assertThat(statistics.getPrepareStatementCount()).as("count/N+1 없이 한 번 조회").isEqualTo(1L);
        assertThat(statistics.getEntityLoadCount()).as("개인정보 Entity 없이 ID projection만 조회").isZero();
        assertThat(queryPort.findActiveAdminIdsAfter(2L, 1)).containsExactly(100L);
        assertThat(queryPort.findActiveAdminIdsAfter(100L, 100)).isEmpty();
        assertThat(queryPort.findActiveAdminIdsAfter(Long.MAX_VALUE, 100)).isEmpty();
        entityManager.clear();
        MemberEntity active = entityManager.find(MemberEntity.class, 2L);
        assertThat(active.getVersion()).isZero();
        assertThat(active.getRole()).isEqualTo(MemberRole.ADMIN);
        assertThat(active.getDeactivatedAt()).isNull();
    }

    @Test
    @DisplayName("101명 이상도 size 상한을 지키며 마지막 ID를 이어 받으면 중복·누락 없이 끝난다")
    void keysetPages() {
        List<Long> expected = new ArrayList<>();
        for (long id = 205L; id > 100L; id--) {
            save(id, MemberRole.ADMIN, null);
            expected.addFirst(id);
        }
        entityManager.flush();
        entityManager.clear();

        List<Long> first = queryPort.findActiveAdminIdsAfter(0L, 100);
        List<Long> second = queryPort.findActiveAdminIdsAfter(first.getLast(), 100);
        List<Long> all = new ArrayList<>(first);
        all.addAll(second);

        assertThat(first).hasSize(100);
        assertThat(second).hasSize(5);
        assertThat(all).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(queryPort.findActiveAdminIdsAfter(second.getLast(), 100)).isEmpty();
    }

    @Test
    @DisplayName("활성 운영자가 없으면 빈 목록이며 복합 인덱스가 role/deactivated_at/id 순서로 존재한다")
    void emptyAndIndexDefinition() {
        assertThat(queryPort.findActiveAdminIdsAfter(0L, 100)).isEmpty();
        String definition = jdbcTemplate.queryForObject("""
            select indexdef from pg_indexes
             where schemaname = 'auth' and indexname = 'idx_member_role_deactivated_at_id'
            """, String.class);
        assertThat(definition).contains("(role, deactivated_at, id)");
    }

    private void save(long id, MemberRole role, Instant deactivatedAt) {
        entityManager.persist(MemberEntity.builder().id(id).email("member" + id + "@example.com")
            .name("테스트").passwordHash("test-only-hash").role(role).deactivatedAt(deactivatedAt).build());
    }
}
