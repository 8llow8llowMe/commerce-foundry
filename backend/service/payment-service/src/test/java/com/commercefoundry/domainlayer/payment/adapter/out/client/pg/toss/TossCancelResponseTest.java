package com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.payment.application.port.out.query.PgCancelResult;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import feign.FeignException;
import feign.Request;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TossCancelResponseTest {
    private final TossPaymentsResponseSupport support = new TossPaymentsResponseSupport(CircuitBreakerRegistry.ofDefaults(), JsonMapper.builder().build());

    @Test
    void acceptsOnlyMatchingKeyOrderStatusAndBalance() {
        Money original = Money.of(129_000, CurrencyCode.KRW);
        Money canceled = Money.of(10_000, CurrencyCode.KRW);
        String response = "{\"paymentKey\":\"pk-test\",\"orderId\":\"9000000001\",\"status\":\"PARTIAL_CANCELED\","
            + "\"currency\":\"KRW\",\"totalAmount\":129000,\"balanceAmount\":119000}";
        assertThat(support.cancel("9000000001", "pk-test", original, canceled,
            () -> response.getBytes(StandardCharsets.UTF_8))).isInstanceOf(PgCancelResult.Succeeded.class);
        assertThat(support.cancel("9000000001", "pk-other", original, canceled,
            () -> response.getBytes(StandardCharsets.UTF_8))).isInstanceOf(PgCancelResult.Unknown.class);
        assertThat(support.cancel("9000000001", "pk-test", original, Money.of(20_000, CurrencyCode.KRW),
            () -> response.getBytes(StandardCharsets.UTF_8))).isInstanceOf(PgCancelResult.Unknown.class);
    }

    @Test
    void permanentCancelRejectionIsTerminalButProcessingConflictRemainsUnknown() {
        Money original = Money.of(129_000, CurrencyCode.KRW);
        Money canceled = Money.of(10_000, CurrencyCode.KRW);
        assertThat(support.cancel("9000000001", "pk-test", original, canceled,
            () -> { throw error(403, "NOT_PARTIAL_CANCELABLE_PAYMENT"); })).isInstanceOf(PgCancelResult.Rejected.class);
        assertThat(support.cancel("9000000001", "pk-test", original, canceled,
            () -> { throw error(409, "IDEMPOTENT_REQUEST_PROCESSING"); })).isInstanceOf(PgCancelResult.Unknown.class);
    }

    private static FeignException error(int status, String code) {
        Request request = Request.create(Request.HttpMethod.POST, "https://example.invalid/v1/payments/key/cancel", Map.of(),
            new byte[0], StandardCharsets.UTF_8, null);
        byte[] body = ("{\"code\":\"" + code + "\",\"message\":\"error\"}").getBytes(StandardCharsets.UTF_8);
        return FeignException.errorStatus("cancel", Response.builder().status(status).reason("test").request(request).body(body).build());
    }
}
