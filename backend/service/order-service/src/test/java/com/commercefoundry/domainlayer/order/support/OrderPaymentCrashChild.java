package com.commercefoundry.domainlayer.order.support;

import com.commercefoundry.domainlayer.order.adapter.out.event.OrderEventAdapter;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.OrderRepositoryAdapter;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.OrderPaymentRepositoryAdapter;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.OrderStockConfirmationRepositoryAdapter;
import com.commercefoundry.domainlayer.order.application.command.PaymentApprovalCommand;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.application.mapper.OrderPaymentMapperImpl;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockReservationPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockConfirmationQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import com.commercefoundry.domainlayer.order.application.service.OrderPaymentEventFacade;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderStockProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.PaymentApprovalProcessor;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.global.messaging.JpaOutboxStore;
import com.commercefoundry.global.messaging.JpaProcessedEventStore;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.messaging.publish.EventPublisher;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.SortedMap;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Test-only JVM entry point: real order PostgreSQL and parent-owned persistent HTTP fake state. */
public final class OrderPaymentCrashChild {
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(Config.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run()) {
            context.getBean(OrderPaymentEventFacade.class).approve(new PaymentApprovalCommand(UUID.fromString(env("CASE_EVENT")), number("CASE_PAYMENT"),
                number("CASE_ORDER"), 7L, Money.of(4000, CurrencyCode.KRW), NOW, "CARD"));
        }
    }

    static String env(String key) { return System.getenv(key); }
    static long number(String key) { return Long.parseLong(env(key)); }

    static String request(String path) {
        try {
            var response = HTTP.send(HttpRequest.newBuilder(URI.create(env("CASE_HTTP") + path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("test HTTP request failed");
            }
            return response.body();
        } catch (Exception exception) {
            throw new IllegalStateException("test HTTP transport failed");
        }
    }

    /** Pause in a test-only port decorator after receiving HTTP 200; no production failpoint. */
    static void barrier(String stage) {
        if (stage.equals(env("CASE_STOP"))) {
            request("/barrier");
            for (;;) { LockSupport.parkNanos(Duration.ofMillis(100).toNanos()); }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackages = {"com.commercefoundry.domainlayer.order.adapter.out.persistence.entity", "com.commercefoundry.global.messaging"})
    @EnableJpaRepositories(basePackages = {"com.commercefoundry.domainlayer.order.adapter.out.persistence.repository", "com.commercefoundry.global.messaging"})
    @Import({QuerydslConfigurer.class, OrderServiceFeaturesConfig.class, OrderRepositoryAdapter.class, OrderMapperImpl.class, OrderPaymentRepositoryAdapter.class,
        OrderPaymentMapperImpl.class, OrderStockConfirmationRepositoryAdapter.class, JpaProcessedEventStore.class, JpaOutboxStore.class,
        OrderEventAdapter.class, OrderPaymentProcessor.class, PaymentApprovalProcessor.class, OrderStockProcessor.class, OrderPaymentEventFacade.class})
    public static class Config {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean SnowflakeIdGenerator ids() { return new SnowflakeIdGenerator(5, 5); }
        @Bean TopicNames topics() { return new TopicNames("commerce-foundry.test", "order"); }
        @Bean EventPublisher publisher(JpaOutboxStore store, Clock clock) {
            return new EventPublisher(() -> store, new EventEnvelopeCodec(65536), "order", clock);
        }
        @Bean PaymentQueryPort payment() {
            return id -> Optional.of(new PaymentQueryResult(id, number("CASE_ORDER"), 7L, request("/payment"), Money.of(4000, CurrencyCode.KRW), NOW, "CARD"));
        }
        @Bean StockReservationPort stock() {
            return new StockReservationPort() {
                public StockReservationQueryResult reserve(long order, SortedMap<Long, Integer> quantities) { throw new UnsupportedOperationException(); }
                public StockReservationQueryResult reReserve(long order, SortedMap<Long, Integer> quantities) {
                    return new StockReservationQueryResult(Long.parseLong(request("/reserve")), order, NOW.plusSeconds(1800));
                }
                public StockConfirmationQueryResult confirm(long reservation) {
                    if (request("/confirm/" + reservation).equals("RELEASED")) {
                        throw new StockReservationException(StockReservationException.Failure.RELEASED);
                    }
                    barrier("confirm");
                    return new StockConfirmationQueryResult(reservation, number("CASE_ORDER"));
                }
                public void release(long reservation, long order) {
                    if (request("/release/" + reservation).equals("CONFIRMED")) {
                        throw new StockReservationException(StockReservationException.Failure.CONFIRMED);
                    }
                }
            };
        }
        @Bean StockRestorationPort restoration() {
            return (claim, reservation, quantities) -> {
                request("/restore/" + claim + "/" + reservation + "/" + quantities.get(101L));
                barrier("restore");
            };
        }
    }
}
