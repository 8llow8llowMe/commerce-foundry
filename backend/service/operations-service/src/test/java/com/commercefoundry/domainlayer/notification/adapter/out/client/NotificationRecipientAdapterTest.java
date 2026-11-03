package com.commercefoundry.domainlayer.notification.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.NotificationRecipientClient;
import com.commercefoundry.domainlayer.notification.adapter.out.client.support.NotificationRecipientInternalResponseSupport;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class NotificationRecipientAdapterTest {
    private final NotificationRecipientClient client = mock(NotificationRecipientClient.class);
    private final NotificationRecipientAdapter adapter = new NotificationRecipientAdapter(client,
        new NotificationRecipientInternalResponseSupport(CircuitBreakerRegistry.ofDefaults()));

    @Test
    void completesAllPagesBeforeReturning() {
        when(client.page(null, 100)).thenReturn(page(LongStream.rangeClosed(1, 100).mapToObj(Long::toString).toList()));
        when(client.page(100L, 100)).thenReturn(page(List.of("101")));
        assertThat(adapter.findAllActiveAdminIds()).hasSize(101).endsWith(100L, 101L);
    }

    @Test
    void malformedEmptyUnorderedAndNoncanonicalPagesFailClosed() {
        for (List<String> values : List.of(List.<String>of(), List.of("0"), List.of("01"), List.of("2", "1"), List.of("1", "1"),
            List.of("9223372036854775808"), List.of("secret-body"))) {
            when(client.page(null, 100)).thenReturn(page(values));
            assertThatThrownBy(adapter::findAllActiveAdminIds).isInstanceOf(NotificationException.class).hasNoCause()
                .hasMessageNotContaining("secret-body");
        }
    }

    @Test
    void laterPageFailureAndRecipientCapFailClosed() {
        when(client.page(null, 100)).thenReturn(page(LongStream.rangeClosed(1, 100).mapToObj(Long::toString).toList()));
        when(client.page(100L, 100)).thenThrow(new IllegalStateException("private-response"));
        assertThatThrownBy(adapter::findAllActiveAdminIds).isInstanceOf(NotificationException.class).hasNoCause();
        reset(client);
        when(client.page(any(), eq(100))).thenAnswer(invocation -> {
            Long after = invocation.getArgument(0);
            long first = after == null ? 1 : after + 1;
            return page(LongStream.range(first, first + 100).mapToObj(Long::toString).toList());
        });
        assertThatThrownBy(adapter::findAllActiveAdminIds).isInstanceOf(NotificationException.class);
        verify(client, times(11)).page(any(), eq(100));
    }

    private Response<NotificationRecipientClient.RecipientPage> page(List<String> ids) {
        return Response.success(new NotificationRecipientClient.RecipientPage(ids));
    }
}
