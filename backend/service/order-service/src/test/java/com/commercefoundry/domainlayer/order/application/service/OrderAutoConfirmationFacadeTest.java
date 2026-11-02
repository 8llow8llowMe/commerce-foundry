package com.commercefoundry.domainlayer.order.application.service;

import static org.mockito.Mockito.*;
import com.commercefoundry.domainlayer.order.application.model.OrderConfirmationCandidate;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderAutoConfirmationProcessor;
import com.commercefoundry.global.properties.OrderAutoConfirmationProperties;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class OrderAutoConfirmationFacadeTest {
    @Test
    void fixesMicrosecondCutoffAndContinuesAfterFailureWithBoundedKeyset() {
        var processor = mock(OrderAutoConfirmationProcessor.class);
        var clock = mock(Clock.class);
        Instant now = Instant.parse("2026-11-02T00:00:00.123456789Z");
        Instant cutoff = Instant.parse("2026-10-26T00:00:00.123456Z");
        when(clock.instant()).thenReturn(now, now.plusSeconds(999));
        var a = new OrderConfirmationCandidate(1, cutoff);
        var b = new OrderConfirmationCandidate(2, cutoff);
        var c = new OrderConfirmationCandidate(3, cutoff);
        when(processor.findDue(cutoff, null, 2)).thenReturn(List.of(a, b));
        when(processor.findDue(cutoff, b, 2)).thenReturn(List.of(c, new OrderConfirmationCandidate(4, cutoff)));
        when(processor.confirm(1, cutoff)).thenThrow(new IllegalStateException("private row message"));
        when(processor.confirm(2, cutoff)).thenThrow(new OptimisticLockingFailureException("race"));
        var settings = new OrderAutoConfirmationProperties(true, Duration.ofDays(7), Duration.ofHours(1), 2, 2);
        new OrderAutoConfirmationFacade(processor, settings, clock).confirmDeliveredOrders();
        verify(processor).confirm(3, cutoff);
        verify(processor).confirm(4, cutoff);
        verify(processor, times(2)).findDue(eq(cutoff), any(), eq(2));
        verify(clock).instant();
    }
}
