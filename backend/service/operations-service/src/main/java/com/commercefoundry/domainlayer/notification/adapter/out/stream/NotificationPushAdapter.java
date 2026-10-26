package com.commercefoundry.domainlayer.notification.adapter.out.stream;

import com.commercefoundry.domainlayer.notification.application.model.NotificationPush;
import com.commercefoundry.domainlayer.notification.application.model.NotificationStream;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
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
    public void push(long recipientMemberId, NotificationPush message) {
        CopyOnWriteArrayList<NotificationStream> group = streams.get(recipientMemberId);
        if (group == null) {
            return;
        }
        for (NotificationStream stream : List.copyOf(group)) {
            try {
                stream.send(message);
            } catch (IOException | RuntimeException exception) {
                unregister(recipientMemberId, stream);
            }
        }
    }
}
