package com.commercefoundry.domainlayer.notification.adapter.out.stream;

import com.commercefoundry.domainlayer.notification.application.model.NotificationPush;
import com.commercefoundry.domainlayer.notification.application.model.NotificationStream;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** 인스턴스 메모리의 SSE 연결. 다른 인스턴스에 붙은 화면은 목록 조회로 따라잡는다 (#213). */
@Component
public class NotificationPushAdapter implements NotificationPushPort {

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<NotificationStream>> streams = new ConcurrentHashMap<>();

    @Override
    public void register(long recipientMemberId, NotificationStream stream) {
        streams.computeIfAbsent(recipientMemberId, ignored -> new CopyOnWriteArrayList<>()).addIfAbsent(stream);
    }

    @Override
    public void unregister(long recipientMemberId, NotificationStream stream) {
        CopyOnWriteArrayList<NotificationStream> group = streams.get(recipientMemberId);
        if (group == null) {
            return;
        }
        group.remove(stream);
        if (group.isEmpty()) {
            streams.remove(recipientMemberId, group);
        }
    }

    @Override
    public void push(Notification notification) {
        // 식별자 문자열 · metadata 는 여기서 만든다. 컨트롤러는 Presenter 를 부르지 못하고(H1), 이 전송은 요청 응답이 아니다.
        NotificationPush message = NotificationPush.from(notification);
        CopyOnWriteArrayList<NotificationStream> group = streams.get(notification.recipientMemberId());
        if (group == null) {
            return;
        }
        for (NotificationStream stream : List.copyOf(group)) {
            try {
                stream.send(message);
            } catch (IOException | RuntimeException exception) {
                unregister(notification.recipientMemberId(), stream);
            }
        }
    }
}
