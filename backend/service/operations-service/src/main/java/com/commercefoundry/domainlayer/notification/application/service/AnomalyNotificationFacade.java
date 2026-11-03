package com.commercefoundry.domainlayer.notification.application.service;

import com.commercefoundry.domainlayer.notification.application.command.AnomalyRecordCommand;
import com.commercefoundry.domainlayer.notification.application.port.in.AnomalyNotificationUseCase;
import com.commercefoundry.domainlayer.notification.application.port.in.AnomalyRetentionSchedulerUseCase;
import com.commercefoundry.domainlayer.notification.application.service.processor.AnomalyNotificationProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnomalyNotificationFacade implements AnomalyNotificationUseCase, AnomalyRetentionSchedulerUseCase {
    private final AnomalyNotificationProcessor processor;

    /** 원격 I/O는 호출자 TX도 일시 중지한다. 수신자 목록을 한번 읽은 후 각 충돌마다 새 TX로 재판정한다. */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void process(AnomalyRecordCommand command) {
        if (processor.alreadyProcessed(command)) {
            return;
        }
        var recipients = processor.findRecipients();
        for (int attempt = 1; ; attempt++) {
            try {
                processor.record(command, recipients);
                return;
            } catch (OptimisticLockingFailureException exception) {
                if (attempt == 3) {
                    throw new IllegalStateException("Anomaly notification concurrency retries exhausted");
                }
            }
        }
    }

    @Override
    public int purgeExpiredRecords() {
        return processor.purgeExpiredRecords();
    }
}
