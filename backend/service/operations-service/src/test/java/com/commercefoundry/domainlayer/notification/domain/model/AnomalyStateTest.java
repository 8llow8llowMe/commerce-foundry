package com.commercefoundry.domainlayer.notification.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.notification.application.command.AnomalyRecordCommand;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AnomalyStateTest {
    @Test
    void rollingWindowReopensExactlyAtLastEmissionPlusWindow() {
        Instant emitted = Instant.parse("2026-11-03T00:59:59Z");
        AnomalyState state = new AnomalyState(1, "commerce-foundry.test.payment.payment-approved.dlt", emitted, emitted, 0L);
        assertThat(state.shouldNotify(emitted.plusSeconds(3599), Duration.ofHours(1))).isFalse();
        assertThat(state.shouldNotify(emitted.plusSeconds(3600), Duration.ofHours(1))).isTrue();
        assertThat(state.observe(emitted.plusSeconds(20), false).lastNotifiedAt()).isEqualTo(emitted);
        assertThat(state.observe(emitted.plusSeconds(3600), true).lastNotifiedAt()).isEqualTo(emitted.plusSeconds(3600));
    }

    @Test
    void safeIdentityRejectsForeignSuffixAndNegativePositions() {
        assertThatThrownBy(() -> new AnomalyRecordCommand(AnomalySource.PAYMENT_APPROVED, "private-topic", 0, 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid anomaly record identity");
        assertThatThrownBy(() -> new AnomalyRecordCommand(AnomalySource.PAYMENT_APPROVED, "commerce-foundry.test.payment.payment-approved.dlt", -1, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(AnomalySource.PAYMENT_APPROVED.notification(7, 1).severity()).isEqualTo(NotificationSeverity.CRITICAL);
        assertThat(AnomalySource.ORDER_EXPIRED.notification(7, 1).severity()).isEqualTo(NotificationSeverity.WARNING);
    }
}
