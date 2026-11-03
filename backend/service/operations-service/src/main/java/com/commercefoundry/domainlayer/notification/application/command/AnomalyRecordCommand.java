package com.commercefoundry.domainlayer.notification.application.command;

import com.commercefoundry.domainlayer.notification.domain.model.AnomalySource;
import java.util.Objects;

/** Kafka 타입 / payload / key / headers와 분리된 안전한 입력이다. */
public record AnomalyRecordCommand(AnomalySource source, String topic, int partition, long offset) {
    public AnomalyRecordCommand {
        Objects.requireNonNull(source, "source");
        if (topic == null || topic.length() > 249 || !topic.matches("[a-z0-9][a-z0-9.-]*")
            || !topic.endsWith("." + source.topicSuffix()) || partition < 0 || offset < 0) {
            throw new IllegalArgumentException("Invalid anomaly record identity");
        }
    }
}
