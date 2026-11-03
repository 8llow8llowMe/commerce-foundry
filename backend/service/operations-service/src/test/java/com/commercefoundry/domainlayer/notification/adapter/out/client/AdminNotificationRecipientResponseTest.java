package com.commercefoundry.domainlayer.notification.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.AdminNotificationRecipientClient.RecipientPage;
import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.AdminNotificationRecipientClient;
import com.commercefoundry.domainlayer.notification.adapter.out.client.support.AdminNotificationRecipientInternalResponseSupport;
import com.commercefoundry.global.properties.AnomalyNotificationProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class AdminNotificationRecipientResponseTest {
    private final AdminNotificationRecipientClient client = mock(AdminNotificationRecipientClient.class);
    private final CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
    private final AdminNotificationRecipientInternalResponseSupport support = new AdminNotificationRecipientInternalResponseSupport(
        circuits, new AnomalyNotificationProperties(false, Duration.ofHours(1), Duration.ofDays(8), 1000, 3, Duration.ofSeconds(1)));

    @Test
    void exactlyOneThousandAllowsFinalEmptyPageAndStrictCursor() {
        when(client.find(anyLong(), eq(100))).thenAnswer(call -> {
            long after = call.getArgument(0);
            return Response.success(new RecipientPage(LongStream.rangeClosed(after + 1, Math.min(after + 100, 1000)).mapToObj(Long::toString).toList()));
        });
        assertThat(support.call(client).memberIds()).hasSize(1000).startsWith(1L).endsWith(1000L);
        verify(client).find(1000L, 100);
        verify(client, times(11)).find(anyLong(), eq(100));
    }

    @Test
    void malformedOrEmptyResponsesAreSanitizedWithoutCause() {
        List<List<String>> pages = new ArrayList<>();
        pages.add(List.of());
        pages.add(List.of("01"));
        pages.add(List.of("+1"));
        pages.add(List.of("0"));
        pages.add(List.of("-1"));
        pages.add(List.of("9223372036854775808"));
        pages.add(List.of("2", "1"));
        pages.add(List.of("1", "1"));
        pages.add(java.util.Arrays.asList((String) null));
        pages.add(LongStream.rangeClosed(1, 101).mapToObj(Long::toString).toList());
        for (List<String> page : pages) {
            when(client.find(0, 100)).thenReturn(Response.success(new RecipientPage(page)));
            assertUnavailable();
        }
        when(client.find(0, 100)).thenReturn(Response.success(new RecipientPage(List.of(7L))));
        assertUnavailable();
        when(client.find(0, 100)).thenReturn(null);
        assertUnavailable();
        when(client.find(0, 100)).thenReturn(Response.fail("private-error", "private-body"));
        assertUnavailable();
        when(client.find(0, 100)).thenThrow(new IllegalStateException("private-body"));
        assertUnavailable();
    }

    @Test
    void cursorRegressionAndMoreThanOneThousandAreRejected() {
        when(client.find(anyLong(), eq(100))).thenAnswer(call -> Response.success(new RecipientPage(
            LongStream.rangeClosed(1, 100).mapToObj(Long::toString).toList())));
        assertUnavailable();
        reset(client);
        when(client.find(anyLong(), eq(100))).thenAnswer(call -> {
            long after = call.getArgument(0);
            return Response.success(new RecipientPage(LongStream.rangeClosed(after + 1, after + 100).mapToObj(Long::toString).toList()));
        });
        assertUnavailable();
        verify(client).find(1000, 100);
    }

    private void assertUnavailable() {
        circuits.circuitBreaker("auth-service").reset();
        assertThatThrownBy(() -> support.call(client)).isInstanceOf(IllegalStateException.class)
            .hasMessage("Admin notification recipients unavailable").hasNoCause();
    }
}
