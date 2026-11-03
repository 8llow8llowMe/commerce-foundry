package com.commercefoundry.domainlayer.notification.application.port.in;

import com.commercefoundry.domainlayer.notification.application.command.DltAlertCommand;

public interface DltAlertEventUseCase {
    void receive(DltAlertCommand command);
}
