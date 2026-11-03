package com.commercefoundry.domainlayer.notification.application.port.out;

public interface PaymentOutboxAlertLockPort {
    void runLocked(Runnable action);
}
