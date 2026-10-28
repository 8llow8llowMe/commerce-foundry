package com.commercefoundry.global.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.messaging.cleanup.MessagingCleanup;
import com.commercefoundry.messaging.properties.MessagingProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbc.JdbcLockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 받기만 하는 product-service 의 처리 기록 정리를 실제 락 경로로 본다(#378) — 실제 {@link JpaProcessedEventStore} 와
 * ShedLock {@code JdbcLockProvider}({@code product.shedlock}, 서비스 yml 의 {@code outbox.relay.lock-table} 과 같은 값)로 {@link MessagingCleanup} 을 한 번 돌린다.
 *
 * <p>컨텍스트 로딩 테스트와 같은 H2(PostgreSQL 모드) 컨텍스트를 쓴다 — {@code ddl-auto} 가 {@code ShedLockEntity} 로 락 테이블을 만든다.
 * 메시징이 꺼진 프로파일이라 백그라운드 정리는 돌지 않고, 여기서 직접 만든 정리만 돈다. 컨텍스트를 공유하므로 이 테스트만의 컨슈머 그룹으로 센다.
 */
@SpringBootTest
@ActiveProfiles("test")
class MessagingCleanupLockTest {

    private static final String GROUP = "commerce-foundry.test.product.cleanup-lock-test";
    private static final String LOCK_TABLE = "product.shedlock";
    private static final Instant NOW = Instant.parse("2026-10-28T09:00:00Z");

    @Autowired
    private JpaProcessedEventStore processedEventStore;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MessagingProperties messagingProperties;

    @Test
    @DisplayName("보존 기간(7일)이 지난 처리 기록만 지우고, 정리 락 행(commerce-messaging-outbox-cleanup)이 product.shedlock 에 남는다")
    void cleansExpiredRecordsUnderJdbcLock() {
        assertThat(messagingProperties.outbox().relay().lockTable()).as("서비스 yml 의 락 테이블").isEqualTo(LOCK_TABLE);
        UUID expired = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            processedEventStore.record(GROUP, expired, "order-expired", NOW.minus(Duration.ofDays(8)));
            processedEventStore.record(GROUP, recent, "order-expired", NOW.minus(Duration.ofHours(1)));
        });
        MessagingCleanup cleanup = new MessagingCleanup(null, processedEventStore,
            new DefaultLockingTaskExecutor(new JdbcLockProvider(dataSource, LOCK_TABLE)), messagingProperties, Clock.fixed(NOW, ZoneOffset.UTC));

        cleanup.cleanupOnce();

        List<UUID> remaining = jdbcTemplate.queryForList("select event_id from product.processed_event where consumer_group = ?", UUID.class, GROUP);
        assertThat(remaining).containsExactly(recent);
        Integer locks = jdbcTemplate.queryForObject("select count(*) from product.shedlock where name = ?", Integer.class, "commerce-messaging-outbox-cleanup");
        assertThat(locks).isEqualTo(1);
    }
}
