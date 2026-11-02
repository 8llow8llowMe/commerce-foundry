package com.commercefoundry.domainlayer.order.application.service.processor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.OrderRequestClaim;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRequestRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequestItem;
import com.commercefoundry.global.properties.OrderProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class OrderRequestProcessorTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final List<OrderRequestItem> ITEMS = List.of(new OrderRequestItem(7101, 2));
    private final OrderRequestRepositoryPort repository = mock(OrderRequestRepositoryPort.class);
    private final OrderRequestProcessor processor = new OrderRequestProcessor(repository, new SnowflakeIdGenerator(1, 4),
        new OrderProperties(new OrderProperties.Creation(Duration.ofSeconds(30), 5, Duration.ofMinutes(30), Duration.ofSeconds(10))), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void absentRequestGetsStableOrderIdentityAndCompletedInsertRaceReplaysWinner() {
        UUID key = UUID.randomUUID();
        String hash = "a".repeat(64);
        when(repository.findByMemberIdAndIdempotencyKey(7, key)).thenReturn(Optional.empty());
        assertThat(processor.check(7, key, hash).outcome()).isEqualTo(OrderRequestClaim.Outcome.NONE);
        when(repository.insertIfAbsent(any(), any())).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        var claim = processor.acquire(7, key, hash, OrderRequestClaim.none(), ITEMS);
        assertThat(claim.orderId()).isPositive();
        assertThat(claim.request().memberId()).isEqualTo(7);
        assertThat(claim.request().idempotencyKey()).isEqualTo(key);
        assertThat(claim.request().leaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
        doReturn(Optional.empty()).when(repository).insertIfAbsent(any(), any());
        when(repository.findByMemberIdAndIdempotencyKey(7, key)).thenReturn(Optional.of(claim.request().complete()));
        assertThat(processor.acquire(7, key, hash, OrderRequestClaim.none(), ITEMS).isReplay()).isTrue();
    }

    @Test
    void activeRequestsAreCountedInTheReservationWindowAndCapIsInclusive() {
        Instant since = NOW.minus(Duration.ofMinutes(30));
        when(repository.countActiveSince(7, since, 0)).thenReturn(4L);
        assertThatCode(() -> processor.requireCapacity(7, null)).doesNotThrowAnyException();

        when(repository.countActiveSince(7, since, 0)).thenReturn(5L);
        assertThatThrownBy(() -> processor.requireCapacity(7, null)).isInstanceOfSatisfying(OrderException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.ACTIVE_ORDER_REQUESTS_EXCEEDED));
        assertThat(OrderErrorCode.ACTIVE_ORDER_REQUESTS_EXCEEDED.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void ownRecordIsExcludedFromTheCount() {
        var own = OrderRequest.start(11, 7, UUID.randomUUID(), "a".repeat(64), 12, NOW, Duration.ofSeconds(30));
        Instant since = NOW.minus(Duration.ofMinutes(30));
        when(repository.countActiveSince(7, since, 11)).thenReturn(4L);
        assertThatCode(() -> processor.requireCapacity(7, own)).doesNotThrowAnyException();
        verify(repository).countActiveSince(7, since, 11);
    }

    @Test
    void creationSettingsRejectNonPositiveCapAndOutOfRangeWindow() {
        assertThatThrownBy(() -> new OrderProperties.Creation(Duration.ofSeconds(30), 0, Duration.ofMinutes(30), Duration.ofSeconds(10)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderProperties.Creation(Duration.ofSeconds(30), -1, Duration.ofMinutes(30), Duration.ofSeconds(10)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderProperties.Creation(Duration.ofSeconds(30), 5, Duration.ofSeconds(59), Duration.ofSeconds(10)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderProperties.Creation(Duration.ofSeconds(30), 5, Duration.ofHours(25), Duration.ofSeconds(10)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderProperties.Creation(Duration.ofSeconds(30), 5, null, Duration.ofSeconds(10)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void creationSettingsRequireWindowAtLeastLeaseAndMarginInsideLease() {
        assertThatThrownBy(() -> new OrderProperties.Creation(Duration.ofMinutes(2), 5, Duration.ofMinutes(1), Duration.ofSeconds(10)))
            .as("창이 lease 보다 짧다").isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new OrderProperties.Creation(Duration.ofMinutes(2), 5, Duration.ofMinutes(2), Duration.ofSeconds(10))).doesNotThrowAnyException();
        for (Duration margin : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(30), Duration.ofSeconds(31)}) {
            assertThatThrownBy(() -> new OrderProperties.Creation(Duration.ofSeconds(30), 5, Duration.ofMinutes(30), margin))
                .as("margin %s", margin).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatCode(() -> new OrderProperties.Creation(Duration.ofSeconds(30), 5, Duration.ofMinutes(30), Duration.ofSeconds(29))).doesNotThrowAnyException();
    }

    @Test
    void invalidClaimAndDigestAreRejectedBeforeDatabaseAndLeaseOutageDoesNotHideOriginalFailure() {
        assertThatThrownBy(() -> processor.check(1, UUID.randomUUID(), "private-input")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("private-input");
        assertThatThrownBy(() -> processor.acquire(1, UUID.randomUUID(), "a".repeat(64), null, ITEMS)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
        var request = OrderRequest.start(1, 2, UUID.randomUUID(), "a".repeat(64), 3, NOW, Duration.ofSeconds(30));
        when(repository.endLease(any(), any())).thenThrow(new IllegalStateException("private-storage-message"));
        assertThatCode(() -> processor.endLeaseQuietly(request)).doesNotThrowAnyException();
    }

    @Test
    void newRequestIsInsertedWithItsItemsAndTakeOverLeavesItemsUntouched() {
        UUID key = UUID.randomUUID();
        String hash = "a".repeat(64);
        var items = List.of(new OrderRequestItem(7101, 3), new OrderRequestItem(7102, 1));
        when(repository.insertIfAbsent(any(), any())).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        var claim = processor.acquire(7, key, hash, OrderRequestClaim.none(), items);
        verify(repository).insertIfAbsent(claim.request(), items);

        var expired = OrderRequest.start(11, 7, key, hash, 12, NOW.minusSeconds(60), Duration.ofSeconds(30));
        var takeOver = new OrderRequestClaim(OrderRequestClaim.Outcome.TAKE_OVER, expired);
        when(repository.takeOver(expired, NOW, Duration.ofSeconds(30))).thenReturn(Optional.of(expired.takeOver(NOW, Duration.ofSeconds(30))));
        assertThat(processor.acquire(7, key, hash, takeOver, items).outcome()).isEqualTo(OrderRequestClaim.Outcome.ACQUIRED);
        verify(repository, times(1)).insertIfAbsent(any(), any());

        assertThatThrownBy(() -> processor.acquire(7, key, hash, OrderRequestClaim.none(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
