package com.commercefoundry.domainlayer.notification.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import com.commercefoundry.domainlayer.notification.application.model.NotificationPush;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRepositoryPort;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationRequest;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

class NotificationProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-26T01:00:00Z");

    @Test
    @DisplayName("같은 작업 · 같은 종류의 알림은 한 번만 저장한다")
    void recordIsIdempotentPerSource() {
        MemoryPort port = new MemoryPort();
        List<NotificationPush> pushed = new ArrayList<>();
        NotificationRecordProcessor processor = new NotificationRecordProcessor(port, pushInto(pushed), new SnowflakeIdGenerator(0, 1));
        NotificationRequest request = request(NotificationKind.JOB_FAILED, NotificationSeverity.WARNING);

        processor.record(request);
        processor.record(request);
        processor.push("JOB", 10L, NotificationKind.JOB_FAILED);

        assertThat(port.saved.get()).isEqualTo(1);
        assertThat(pushed).singleElement().satisfies(message -> {
            assertThat(message.kind().code()).isEqualTo("JOB_FAILED");
            assertThat(message.read()).isFalse();
            assertThat(message.title()).isEqualTo("작업이 실패했습니다");
        });
    }

    @Test
    @DisplayName("다른 어드민의 알림은 없는 것과 같고, 모두 읽으면 읽지 않은 수가 0이다")
    void markReadHidesOthersAndClearsUnread() {
        MemoryPort port = new MemoryPort();
        port.store(Notification.create(1L, request(NotificationKind.JOB_SUCCEEDED, NotificationSeverity.INFO)));
        port.store(Notification.create(2L, new NotificationRequest(
            NotificationKind.ANOMALY, NotificationSeverity.CRITICAL, "확인할 일이 있습니다", null, 7L, "ANOMALY", 3L)));
        NotificationAdminProcessor processor = new NotificationAdminProcessor(port, pushInto(List.of()));

        assertThatThrownBy(() -> processor.markRead(1L, 99L, NOW)).isInstanceOf(NotificationException.class);

        processor.markRead(1L, 7L, NOW);
        assertThat(processor.markRead(1L, 7L, NOW.plusSeconds(1)).readAt()).isEqualTo(NOW);
        assertThat(processor.markAllRead(7L, NOW)).isEqualTo(1);
        assertThat(processor.unreadCount(7L)).isZero();
        assertThat(processor.list(7L, 0, 20).getTotalElements()).isEqualTo(2);
    }

    private static NotificationPushPort pushInto(List<NotificationPush> pushed) {
        return new NotificationPushPort() {
            @Override
            public void register(long recipientMemberId, com.commercefoundry.domainlayer.notification.application.model.NotificationStream stream) {
            }

            @Override
            public void unregister(long recipientMemberId, com.commercefoundry.domainlayer.notification.application.model.NotificationStream stream) {
            }

            @Override
            public void push(long recipientMemberId, NotificationPush message) {
                pushed.add(message);
            }
        };
    }

    private static NotificationRequest request(NotificationKind kind, NotificationSeverity severity) {
        return new NotificationRequest(kind, severity, "작업이 실패했습니다", "/admin/jobs/10", 7L, "JOB", 10L);
    }

    private static final class MemoryPort implements NotificationRepositoryPort {

        private final List<Notification> rows = new ArrayList<>();
        private final AtomicInteger saved = new AtomicInteger();

        void store(Notification notification) {
            rows.add(notification);
        }

        @Override
        public Notification save(Notification notification) {
            saved.incrementAndGet();
            rows.removeIf(row -> row.id() == notification.id());
            Notification stored = new Notification(
                notification.id(), notification.recipientMemberId(), notification.kind(), notification.severity(),
                notification.title(), notification.linkPath(), notification.sourceType(), notification.sourceId(),
                notification.readAt(), notification.createdAt(), 0L);
            rows.add(stored);
            return stored;
        }

        @Override
        public Optional<Notification> findById(long notificationId) {
            return rows.stream().filter(row -> row.id() == notificationId).findFirst();
        }

        @Override
        public Optional<Notification> findBySource(String sourceType, long sourceId, NotificationKind kind) {
            return rows.stream()
                .filter(row -> row.sourceType().equals(sourceType) && row.sourceId() == sourceId && row.kind() == kind)
                .findFirst();
        }

        @Override
        public Page<Notification> findPage(long recipientMemberId, int page, int size) {
            List<Notification> mine = rows.stream().filter(row -> row.recipientMemberId() == recipientMemberId).toList();
            return new PageImpl<>(mine);
        }

        @Override
        public long countUnread(long recipientMemberId) {
            return rows.stream().filter(row -> row.recipientMemberId() == recipientMemberId && row.unread()).count();
        }

        @Override
        public int markAllRead(long recipientMemberId, Instant now) {
            int updated = 0;
            for (int index = 0; index < rows.size(); index++) {
                Notification row = rows.get(index);
                if (row.recipientMemberId() != recipientMemberId || !row.unread()) {
                    continue;
                }
                rows.set(index, row.markRead(now));
                updated++;
            }
            return updated;
        }

        @Override
        public int deleteCreatedBefore(Instant cutoff) {
            return 0;
        }
    }
}
