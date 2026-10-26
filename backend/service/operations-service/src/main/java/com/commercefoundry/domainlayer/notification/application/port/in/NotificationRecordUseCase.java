package com.commercefoundry.domainlayer.notification.application.port.in;

import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationRequest;

/**
 * 작업 종료 이벤트가 알림을 남기고, 커밋 뒤에 스트림으로 민다 (#213).
 */
public interface NotificationRecordUseCase {

    /** 호출 중인 트랜잭션에 참여한다. 같은 출처 · 종류가 있으면 새로 만들지 않는다. */
    void record(NotificationRequest request);

    /** 커밋 뒤에 호출한다. 행이 없으면 아무 것도 하지 않는다. */
    void push(String sourceType, long sourceId, NotificationKind kind);
}
