package com.commercefoundry.domainlayer.notification.application.command;

/** DLT alert metadata only; see anomaly-notifications.md. */
public record DltAlertCommand(String topic, int partition, long offset) {
    public DltAlertCommand {
        if (topic == null || partition < 0 || offset < 0) {
            throw new IllegalArgumentException("Invalid DLT metadata");
        }
    }
}
