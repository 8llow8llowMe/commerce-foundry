package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 모두 읽음의 조건부 UPDATE 를 실제 PostgreSQL 에 질의해 본다 ({@code coding-conventions.md} §9-5).
 * 스키마는 DDL 스냅숏 + {@code ddl-auto=validate} 다. Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import(OperationsServiceFeaturesConfig.class)
class NotificationRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    private static final Instant NOW = Instant.parse("2026-10-26T01:00:00Z");
    private static final Instant ALREADY = Instant.parse("2026-10-25T01:00:00Z");

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    @DisplayName("모두 읽음은 그 어드민의 읽지 않은 행만 한 문장으로 채우고, 이미 읽은 행과 다른 어드민은 그대로다")
    void markUnreadAsReadTouchesOnlyThatAdminsUnreadRows() {
        save(1L, 7L, null, "JOB", 10L);
        save(2L, 7L, ALREADY, "JOB", 11L);
        save(3L, 8L, null, "JOB", 12L);

        assertThat(notificationRepository.markUnreadAsRead(7L, NOW)).isEqualTo(1);
        assertThat(notificationRepository.markUnreadAsRead(7L, NOW.plusSeconds(5))).isZero();

        NotificationEntity read = notificationRepository.findById(1L).orElseThrow();
        NotificationEntity already = notificationRepository.findById(2L).orElseThrow();
        NotificationEntity other = notificationRepository.findById(3L).orElseThrow();

        assertThat(read.getReadAt()).isEqualTo(NOW);
        assertThat(read.getUpdatedAt()).isEqualTo(NOW);
        assertThat(read.getVersion()).isEqualTo(1L);
        assertThat(already.getReadAt()).isEqualTo(ALREADY);
        assertThat(already.getVersion()).isZero();
        assertThat(other.getReadAt()).isNull();
        assertThat(other.getVersion()).isZero();
        assertThat(notificationRepository.countByRecipientMemberIdAndReadAtIsNull(7L)).isZero();
    }

    private void save(long id, long recipientMemberId, Instant readAt, String sourceType, long sourceId) {
        notificationRepository.saveAndFlush(NotificationEntity.builder()
            .id(id)
            .recipientMemberId(recipientMemberId)
            .kind(NotificationKind.JOB_FAILED)
            .severity(NotificationSeverity.WARNING)
            .title("작업이 실패했습니다")
            .sourceType(sourceType)
            .sourceId(sourceId)
            .readAt(readAt)
            .build());
    }
}
