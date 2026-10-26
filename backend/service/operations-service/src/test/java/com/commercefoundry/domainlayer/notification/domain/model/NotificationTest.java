package com.commercefoundry.domainlayer.notification.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationTest {

    private static final Instant NOW = Instant.parse("2026-10-26T01:00:00Z");

    @Test
    @DisplayName("읽지 않은 알림은 읽음으로 바뀌고, 다시 읽으면 같은 인스턴스다")
    void markReadIsIdempotent() {
        Notification notification = sample();

        Notification read = notification.markRead(NOW);
        Notification again = read.markRead(NOW.plusSeconds(5));

        assertThat(read.readAt()).isEqualTo(NOW);
        assertThat(read).isNotSameAs(notification);
        assertThat(again).isSameAs(read);
    }

    @Test
    @DisplayName("제목에 제어 문자가 있으면 요청을 만들지 않는다")
    void rejectsControlCharacterInTitle() {
        assertThatThrownBy(() -> request("작업\n끝")).isInstanceOf(IllegalArgumentException.class);
    }

    private static Notification sample() {
        return Notification.create(10L, request("작업이 끝났습니다"));
    }

    private static NotificationRequest request(String title) {
        return new NotificationRequest(NotificationKind.JOB_SUCCEEDED, NotificationSeverity.INFO, title, "/admin/jobs/10", 7L, "JOB", 10L);
    }
}
