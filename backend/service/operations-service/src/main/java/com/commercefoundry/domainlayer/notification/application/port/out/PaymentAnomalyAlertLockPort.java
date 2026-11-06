package com.commercefoundry.domainlayer.notification.application.port.out;

public interface PaymentAnomalyAlertLockPort {
    void runLocked(Runnable action);
}
