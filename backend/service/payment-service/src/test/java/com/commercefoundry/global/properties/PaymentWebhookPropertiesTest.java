package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentWebhookPropertiesTest {
    @Test void defaultsAreOffBoundedAndBackoffCaps() {
        var defaults = new PaymentWebhookProperties(false, null, null, null, null, null, null);
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.batchSize()).isEqualTo(20);
        assertThat(defaults.maxAttempts()).isEqualTo(6);
        assertThat(defaults.lease()).isEqualTo(Duration.ofSeconds(60));
        assertThat(defaults.backoff(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.backoff(20)).isEqualTo(Duration.ofMinutes(5));
    }
    @Test void invalidBudgetsAndShortLeaseAreRejectedAtBinding() {
        assertThatThrownBy(() -> new PaymentWebhookProperties(true, 101, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentWebhookProperties(true, null, Duration.ofSeconds(29), null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentWebhookProperties(true, null, null, 0, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void generatedLeaseAndRetryTimesAreMicrosecondCanonicalIncludingNanoDurations() {
        Instant now = Instant.parse("2026-11-03T00:00:00.123456789Z");
        var received = PaymentWebhookInbox.received(1, 2, "a".repeat(64), now);
        var claimed = received.claim("owner", now, Duration.ofSeconds(60).plusNanos(999));
        var retry = claimed.finish(PaymentWebhookInbox.State.RETRY, PaymentWebhookInbox.Reason.PG_RETRY,
            now.plusSeconds(5).plusNanos(999), now.plusNanos(999));
        assertThat(received.receivedAt().getNano()).isEqualTo(123456000);
        assertThat(claimed.availableAt().getNano()).isEqualTo(123457000);
        assertThat(retry.availableAt().getNano()).isEqualTo(123457000);
        assertThat(retry.updatedAt().getNano()).isEqualTo(123457000);
    }
}
