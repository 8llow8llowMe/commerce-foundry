package com.commercefoundry.domainlayer.notification.application.port.in;

import com.commercefoundry.domainlayer.notification.application.command.AnomalyRecordCommand;

public interface AnomalyNotificationUseCase {
    void process(AnomalyRecordCommand command);
}
