package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.application.service.processor.NotificationAdminProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 단건·모두 읽음의 조건부 UPDATE와 별도 트랜잭션 경쟁을 실제 PostgreSQL에서 검증한다 ({@code coding-conventions.md} §9-5).
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

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("단건 읽음은 ID·수신자·미읽음 조건을 모두 검사하고 최초 읽음·생성 시각을 보존한다")
    void markReadIfUnreadPreservesFirstTimestampAndOwnership() {
        save(1L, 7L, null, "JOB", 10L);
        save(2L, 7L, ALREADY, "JOB", 11L);
        save(3L, 8L, null, "JOB", 12L);
        // 감사 리스너의 나노초 값 대신 PostgreSQL에 실제 저장된 마이크로초 값을 비교 기준으로 삼는다.
        entityManager.clear();
        Instant createdAt = notificationRepository.findById(1L).orElseThrow().getCreatedAt();

        assertThat(notificationRepository.markReadIfUnread(1L, 8L, NOW)).isZero();
        assertThat(notificationRepository.markReadIfUnread(99L, 7L, NOW)).isZero();
        assertThat(notificationRepository.markReadIfUnread(1L, 7L, NOW)).isEqualTo(1);
        assertThat(notificationRepository.markReadIfUnread(1L, 7L, NOW.plusSeconds(1))).isZero();
        assertThat(notificationRepository.markReadIfUnread(2L, 7L, NOW)).isZero();

        NotificationEntity read = notificationRepository.findById(1L).orElseThrow();
        assertThat(read.getReadAt()).isEqualTo(NOW);
        assertThat(read.getUpdatedAt()).isEqualTo(NOW);
        assertThat(read.getCreatedAt()).isEqualTo(createdAt);
        assertThat(read.getVersion()).isEqualTo(1L);
        assertThat(notificationRepository.findById(2L).orElseThrow().getReadAt()).isEqualTo(ALREADY);
        assertThat(notificationRepository.findById(3L).orElseThrow().getReadAt()).isNull();
    }

    @Test
    @DisplayName("새 단건 조건부 갱신은 혼합 배포 중 구버전 stale merge도 거절한다")
    void conditionalUpdateRejectsLegacyStaleMerge() {
        save(1L, 7L, null, "JOB", 10L);
        NotificationEntity stale = notificationRepository.findById(1L).orElseThrow();

        notificationRepository.markReadIfUnread(1L, 7L, NOW);

        assertThatThrownBy(() -> notificationRepository.saveAndFlush(stale)).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("같은 미읽음 스냅숏의 단건 요청 둘이 모두 성공하고 승자의 시각을 반환한다")
    void concurrentSingleReadsReturnSameStoredTimestamp() throws Exception {
        assertConcurrentReads(false);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("같은 미읽음 스냅숏의 단건·전체 읽음이 모두 성공하고 승자의 시각을 반환한다")
    void concurrentSingleAndAllReadsReturnSameStoredTimestamp() throws Exception {
        assertConcurrentReads(true);
    }

    private void assertConcurrentReads(boolean secondReadsAll) throws Exception {
        notificationRepository.deleteAll();
        try {
            save(1L, 7L, null, "JOB", 10L);
            save(3L, 8L, null, "JOB", 12L);
            Instant createdAt = notificationRepository.findById(1L).orElseThrow().getCreatedAt();
            CyclicBarrier snapshotsRead = new CyclicBarrier(2);
            AtomicInteger reads = new AtomicInteger();
            NotificationRepositoryAdapter adapter = spy(new NotificationRepositoryAdapter(notificationRepository));
            doAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Optional<Notification> snapshot = (Optional<Notification>) invocation.callRealMethod();
                if (reads.incrementAndGet() <= 2) {
                    snapshotsRead.await(10, TimeUnit.SECONDS);
                }
                return snapshot;
            }).when(adapter).findById(anyLong());
            NotificationAdminProcessor processor = new NotificationAdminProcessor(adapter, mock(NotificationPushPort.class));
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setTimeout(15);

            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> transaction.execute(status -> processor.markRead(1L, 7L, NOW)));
                var second = executor.submit(() -> transaction.execute(status -> {
                    if (!secondReadsAll) {
                        return processor.markRead(1L, 7L, NOW.plusSeconds(1));
                    }
                    adapter.findById(1L); // 두 트랜잭션이 같은 미읽음 스냅숏을 읽은 뒤 경쟁시킨다.
                    processor.markAllRead(7L, NOW.plusSeconds(1));
                    return adapter.findById(1L).orElseThrow();
                }));
                Notification firstResult = first.get(20, TimeUnit.SECONDS);
                Notification secondResult = second.get(20, TimeUnit.SECONDS);

                NotificationEntity stored = notificationRepository.findById(1L).orElseThrow();
                assertThat(stored.getReadAt()).isIn(NOW, NOW.plusSeconds(1));
                assertThat(firstResult.readAt()).isEqualTo(stored.getReadAt());
                assertThat(secondResult.readAt()).isEqualTo(stored.getReadAt());
                assertThat(firstResult.createdAt()).isEqualTo(createdAt);
                assertThat(secondResult.createdAt()).isEqualTo(createdAt);
                assertThat(stored.getVersion()).isEqualTo(1L);
                assertThat(notificationRepository.countByRecipientMemberIdAndReadAtIsNull(7L)).isZero();
                assertThat(notificationRepository.findById(3L).orElseThrow().getReadAt()).isNull();
            }
        } finally {
            notificationRepository.deleteAll();
        }
    }

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
