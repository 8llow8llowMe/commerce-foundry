package com.commercefoundry.domainlayer.order.domain.model;

import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.global.properties.OrderProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderRequestTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00.123456789Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void microsecondDeadlineHasExactTakeoverBoundaryAndRetainsIdentity() {
        var request = OrderRequest.start(1, 2, UUID.randomUUID(), HASH, 3, NOW, Duration.ofSeconds(30));
        assertThat(request.leaseExpiresAt()).isEqualTo(Instant.parse("2026-10-28T00:00:30.123456Z"));
        assertThat(request.isLeasedAt(request.leaseExpiresAt().minusNanos(1))).isTrue();
        assertThat(request.isLeasedAt(request.leaseExpiresAt())).isFalse();
        var next = request.takeOver(request.leaseExpiresAt(), Duration.ofSeconds(30));
        assertThat(next.id()).isEqualTo(request.id());
        assertThat(next.orderId()).isEqualTo(request.orderId());
        assertThat(next.requestHash()).isEqualTo(HASH);
        assertThatThrownBy(() -> request.takeOver(NOW, Duration.ofSeconds(30))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completedStateCannotBeTakenOverOrEndedAndDoesNotExposeKeyOrDigest() {
        var request = OrderRequest.start(1, 2, UUID.randomUUID(), HASH, 3, NOW, Duration.ofSeconds(30)).complete();
        assertThatThrownBy(() -> request.takeOver(NOW.plusSeconds(60), Duration.ofSeconds(30))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.endLease(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(request::complete).isInstanceOf(IllegalStateException.class);
        assertThat(request.toString()).doesNotContain(HASH, request.idempotencyKey().toString());
    }

    @Test
    void invalidIdentifiersDigestAndLeaseAreRejectedWithoutEchoingInput() {
        assertThatThrownBy(() -> OrderRequest.start(0, 2, UUID.randomUUID(), HASH, 3, NOW, Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderRequest.start(1, 2, UUID.randomUUID(), "private-input", 3, NOW, Duration.ofSeconds(30)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-input");
        for (Duration duration : new Duration[]{Duration.ZERO, Duration.ofMillis(999), Duration.ofMinutes(6)}) {
            assertThatThrownBy(() -> new OrderProperties.Creation(duration)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OrderRequest.start(1, 2, UUID.randomUUID(), HASH, 3, NOW, duration)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
