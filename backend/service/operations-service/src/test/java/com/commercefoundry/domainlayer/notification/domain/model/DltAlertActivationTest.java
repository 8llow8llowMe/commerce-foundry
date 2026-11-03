package com.commercefoundry.domainlayer.notification.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.notification.adapter.in.event.DltAlertEventListener;
import com.commercefoundry.domainlayer.notification.application.port.in.DltAlertEventUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DltAlertActivationTest {
    @Test
    void listenerRequiresBothFeatureAndMessagingFlags() {
        var runner = new ApplicationContextRunner()
            .withBean(DltAlertEventUseCase.class, () -> mock(DltAlertEventUseCase.class))
            .withUserConfiguration(DltAlertEventListener.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(DltAlertEventListener.class));
        runner.withPropertyValues("operations.notifications.dlt-alert.enabled=true", "commerce.messaging.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(DltAlertEventListener.class));
        runner.withPropertyValues("operations.notifications.dlt-alert.enabled=false", "commerce.messaging.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(DltAlertEventListener.class));
        runner.withPropertyValues("operations.notifications.dlt-alert.enabled=true", "commerce.messaging.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(DltAlertEventListener.class));
    }
}
