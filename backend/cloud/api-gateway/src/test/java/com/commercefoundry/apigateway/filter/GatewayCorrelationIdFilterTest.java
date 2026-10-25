package com.commercefoundry.apigateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.log.CorrelationIds;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class GatewayCorrelationIdFilterTest {

    private static final String ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final GatewayCorrelationIdFilter filter = new GatewayCorrelationIdFilter();

    @BeforeAll
    static void enableContextPropagation() {
        CorrelationIdReactorConfiguration.register();
    }

    @Test
    @DisplayName("가장 앞에서 돈다")
    void runsFirst() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    @DisplayName("맞는 헤더는 소문자로 하류에 하나만 넘기고, 다른 스레드의 로그 MDC 도 그 값이다")
    void forwardsAValidIdOntoTheDownstreamRequestAndLogs() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products")
            .header(CorrelationIds.HEADER, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")
            .header(CorrelationIds.HEADER.toLowerCase(), "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
            .build());
        // 위는 같은 이름이라 값이 둘이다 — 믿지 않고 새로 만든다. 맞는 값 하나만 있는 경우를 따로 본다.
        AtomicReference<String> downstream = new AtomicReference<>();
        filter.filter(exchange, forwarded -> {
            downstream.set(forwarded.getRequest().getHeaders().getFirst(CorrelationIds.HEADER));
            return Mono.empty();
        }).block(TIMEOUT);
        assertThat(downstream.get()).isNotEqualTo(ID).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

        MockServerWebExchange one = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products")
            .header(CorrelationIds.HEADER, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE").build());
        AtomicReference<String> header = new AtomicReference<>();
        AtomicReference<String> onSubscribeThread = new AtomicReference<>();
        AtomicReference<String> logged = new AtomicReference<>();
        filter.filter(one, forwarded -> {
            onSubscribeThread.set(MDC.get(CorrelationIds.MDC_KEY));
            header.set(forwarded.getRequest().getHeaders().getFirst(CorrelationIds.HEADER));
            return Mono.delay(Duration.ofMillis(20), Schedulers.boundedElastic())
                .doOnNext(tick -> logged.set(MDC.get(CorrelationIds.MDC_KEY)))
                .then();
        }).block(TIMEOUT);

        assertThat(header).hasValue(ID);
        assertThat(onSubscribeThread).hasValue(ID);
        assertThat(one.getResponse().getHeaders().get(CorrelationIds.HEADER)).isNull();
        assertThat(logged).hasValue(ID);
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("형식이 틀린 외부 헤더는 버리고 새 id 를 하류에 싣는다 — 틀린 원문은 헤더에 없다")
    void replacesAMalformedHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products")
            .header(CorrelationIds.HEADER, ID + "\nINFO forged").build());
        AtomicReference<HttpHeaders> headers = new AtomicReference<>();
        filter.filter(exchange, forwarded -> {
            headers.set(forwarded.getRequest().getHeaders());
            return Mono.empty();
        }).block(TIMEOUT);

        assertThat(headers.get().get(CorrelationIds.HEADER)).singleElement().asString()
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            .doesNotContain("forged")
            .isNotEqualTo(ID);
    }
}
