package com.commercefoundry.apigateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class GatewaySupportCacheControlFilterTest {
    private final GatewaySupportCacheControlFilter filter = new GatewaySupportCacheControlFilter();

    @Test
    void runsBeforeRequestAndSecurityGuardsAndOverridesDownstreamCaching() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        for (String path : new String[]{"/api/v1/support", "/api/v1/support/conversations/123", "/api/v1/%73upport/conversations/123/messages"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, URI.create(path)));
            filter.filter(exchange, forwarded -> {
                assertThat(forwarded.getResponse().getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
                forwarded.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "public, max-age=60");
                return forwarded.getResponse().setComplete();
            }).block();
            assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        }
    }

    @Test
    void otherPathsKeepTheirExistingCachePolicy() {
        for (String path : new String[]{"/api/v1/products/1", "/api/v1/supporting/1", "/api/v1/admin/support/knowledge"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, URI.create(path)));
            exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "public, max-age=60");
            filter.filter(exchange, forwarded -> Mono.empty()).block();
            assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("public, max-age=60");
        }
    }
}
