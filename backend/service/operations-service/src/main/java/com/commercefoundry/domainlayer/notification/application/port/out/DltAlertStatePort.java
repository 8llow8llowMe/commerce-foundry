package com.commercefoundry.domainlayer.notification.application.port.out;

import com.commercefoundry.domainlayer.notification.domain.model.DltAlertState;

/** DLT alert metadata only; see anomaly-notifications.md. */
public interface DltAlertStatePort {
    DltAlertState lockTopic(String topic);
    long checkpoint(String topic, int partition);
    void advance(String topic, int partition, long offset, DltAlertState state, long checkpointId);
}
