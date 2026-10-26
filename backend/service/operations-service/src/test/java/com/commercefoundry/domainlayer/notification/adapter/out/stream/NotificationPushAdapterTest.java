package com.commercefoundry.domainlayer.notification.adapter.out.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationPushAdapterTest {

    @Test
    @DisplayName("연결된 화면만 받고, 끊긴 연결은 다음 전송에서 빠진다")
    void pushReachesOpenStreamAndDropsBrokenStream() throws IOException {
        NotificationPushAdapter adapter = new NotificationPushAdapter();
        List<String> received = new ArrayList<>();
        adapter.register(7L, message -> received.add(message.notificationId()));
        adapter.register(7L, message -> {
            throw new IOException("closed");
        });

        Notification message = new Notification(
            10L,
            7L,
            NotificationKind.JOB_SUCCEEDED,
            NotificationSeverity.INFO,
            "작업이 끝났습니다",
            "/admin/jobs/10",
            "JOB",
            10L,
            null,
            Instant.parse("2026-10-26T01:00:00Z"),
            0L
        );

        adapter.push(message);
        adapter.push(message);
        adapter.push(new Notification(
            11L, 8L, NotificationKind.JOB_SUCCEEDED, NotificationSeverity.INFO, "작업이 끝났습니다", null, "JOB", 11L, null, null, 0L));

        assertThat(received).containsExactly("10", "10");
    }
}
