package com.commercefoundry.domainlayer.notification.application.service;

import com.commercefoundry.domainlayer.notification.application.port.in.NotificationRecordUseCase;
import com.commercefoundry.domainlayer.notification.application.service.processor.NotificationRecordProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 기록은 호출자의 트랜잭션에 참여한다(작업 종료와 함께 커밋 · 롤백). 밀어 주기는 커밋 뒤 새 읽기 트랜잭션이다 (#213).
 */
@Service
@RequiredArgsConstructor
public class NotificationRecordFacade implements NotificationRecordUseCase {

    private final NotificationRecordProcessor notificationRecordProcessor;

    @Override
    @Transactional
    public void record(NotificationRequest request) {
        notificationRecordProcessor.record(request);
    }

    @Override
    @Transactional(readOnly = true)
    public void push(String sourceType, long sourceId, NotificationKind kind) {
        notificationRecordProcessor.push(sourceType, sourceId, kind);
    }
}
