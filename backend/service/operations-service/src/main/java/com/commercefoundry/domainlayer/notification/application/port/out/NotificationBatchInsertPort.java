package com.commercefoundry.domainlayer.notification.application.port.out;

import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import java.util.List;

/** 호출자 트랜잭션에서 신규 미읽음 알림 1~5000개를 원자 저장한다. 반환 순서와 ID는 입력과 대응한다. */
public interface NotificationBatchInsertPort {
    List<Notification> insertAll(List<Notification> notifications);
}
