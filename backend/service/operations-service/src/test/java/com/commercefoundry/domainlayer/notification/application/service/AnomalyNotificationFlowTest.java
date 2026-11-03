package com.commercefoundry.domainlayer.notification.application.service;

import org.springframework.kafka.test.context.EmbeddedKafka;

@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers", topics = {
    AnomalyNotificationFlowTestBase.PAYMENT, AnomalyNotificationFlowTestBase.EXPIRED, AnomalyNotificationFlowTestBase.CANCELED,
    "commerce-foundry.test.operations.job-run", "commerce-foundry.test.operations.job-run.dlt"
})
class AnomalyNotificationFlowTest extends AnomalyNotificationFlowTestBase {
}
