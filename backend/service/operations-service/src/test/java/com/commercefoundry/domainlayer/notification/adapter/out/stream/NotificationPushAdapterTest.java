package com.commercefoundry.domainlayer.notification.adapter.out.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.notification.application.model.NotificationPush;
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
        List<NotificationPush> received = new ArrayList<>();
        adapter.register(7L, received::add);
        adapter.register(7L, message -> {
            throw new IOException("closed");
        });

        NotificationPush message = new NotificationPush(
            "10",
            new CodeNameDescriptionMetadata("JOB_SUCCEEDED", "작업 완료", "끝"),
            new CodeNameDescriptionMetadata("INFO", "안내", "확인"),
            "작업이 끝났습니다",
            "/admin/jobs/10",
            false,
            Instant.parse("2026-10-26T01:00:00Z")
        );

        adapter.push(7L, message);
        adapter.push(7L, message);
        adapter.push(8L, message);

        assertThat(received).containsExactly(message, message);
    }
}
