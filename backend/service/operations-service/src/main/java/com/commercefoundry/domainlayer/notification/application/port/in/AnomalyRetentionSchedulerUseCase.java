package com.commercefoundry.domainlayer.notification.application.port.in;

public interface AnomalyRetentionSchedulerUseCase {
    int purgeExpiredRecords();
}
