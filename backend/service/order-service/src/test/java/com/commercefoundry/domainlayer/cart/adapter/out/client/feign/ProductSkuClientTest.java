package com.commercefoundry.domainlayer.cart.adapter.out.client.feign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.cart.application.exception.CartErrorCode;
import com.commercefoundry.domainlayer.cart.application.exception.CartException;
import com.commercefoundry.domainlayer.cart.application.port.out.SkuQueryPort;
import com.commercefoundry.domainlayer.cart.application.port.out.query.SkuQueryResult;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 Feign 파이프라인(서비스 토큰 Capability · 인코딩 · Jackson 3 디코딩 · 타임아웃 설정)으로 product-service 흉내 서버를 불러 본다.
 * 원격 계약(쿼리 모양 · 봉투 · 모르는 칸 무시)과 서비스 토큰({@code aud=product-service}, {@code iss=order-service})을 함께 본다.
 *
 * <p>대상 주소만 {@code spring.cloud.openfeign.client.config.productSkuClient.url} 로 바꾼다 — 서비스 토큰의 {@code aud} 는 주소가 아니라 Feign 대상 이름이라 그대로다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductSkuClientTest {

    private static final HttpServer SERVER = startServer();
    private static final AtomicReference<String> RESPONSE_BODY = new AtomicReference<>();
    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);
    private static final List<String> QUERIES = new CopyOnWriteArrayList<>();
    private static final List<List<String>> AUTHORIZATIONS = new CopyOnWriteArrayList<>();

    @Autowired
    private SkuQueryPort skuQueryPort;

    @DynamicPropertySource
    static void productServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.productSkuClient.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/skus", exchange -> {
                QUERIES.add(exchange.getRequestURI().getRawQuery());
                AUTHORIZATIONS.add(exchange.getRequestHeaders().getOrDefault("Authorization", List.of()));
                byte[] body = RESPONSE_BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(RESPONSE_STATUS.get(), body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @BeforeEach
    void reset() {
        QUERIES.clear();
        AUTHORIZATIONS.clear();
        RESPONSE_STATUS.set(200);
    }

    @Test
    @DisplayName("ids 를 반복 파라미터로 보내고, 서비스 토큰 하나를 싣고, product-service 봉투를 조회 결과로 바꾼다 — 모르는 칸은 무시한다")
    void roundTrip() {
        RESPONSE_BODY.set("""
            {"dataHeader":{"success":true,"resultCode":null,"resultMessage":null,"fieldErrors":null},
             "dataBody":{"skus":[
               {"skuId":"9004","productId":"7301","productName":"모코 생활한복 저고리",
                "optionValues":[{"optionGroupName":"색상","optionValueName":"아이보리"}],"optionName":"아이보리",
                "salePrice":{"amount":39000,"currency":"KRW"},"additionalPrice":{"amount":0,"currency":"KRW"},
                "saleStatus":{"code":"SOLD_OUT","name":"품절","description":"재고가 모두 소진되었습니다."},
                "purchasable":false,"maxQuantityPerOrder":10,"customOrder":true,"futureField":1}
             ]}}
            """);

        List<SkuQueryResult> results = skuQueryPort.findSkus(List.of(9004L, 9005L));

        assertThat(results).containsExactly(
            new SkuQueryResult(9004L, 7301L, "모코 생활한복 저고리", "아이보리", Money.of(39_000, CurrencyCode.KRW), false, true, 10, true));
        assertThat(QUERIES).containsExactly("ids=9004&ids=9005");
        assertThat(AUTHORIZATIONS).singleElement().satisfies(values -> {
            assertThat(values).singleElement().asString().startsWith("Bearer ");
            Jwt token = InternalTokenDecoderFactory.create(TestServiceTokens.properties("product-service"), Clock.systemUTC())
                .decode(values.getFirst().substring("Bearer ".length()));
            assertThat(token.getAudience()).containsExactly("product-service");
            assertThat(token.getClaimAsString("iss")).isEqualTo("order-service");
        });
    }

    @Test
    @DisplayName("product-service 의 5xx 는 CART_PRODUCT_SERVICE_UNAVAILABLE 로 바뀐다 — FeignException 이 밖으로 나오지 않는다")
    void serverError() {
        RESPONSE_STATUS.set(503);
        RESPONSE_BODY.set("{\"dataHeader\":{\"success\":false,\"resultCode\":\"X\",\"resultMessage\":\"x\",\"fieldErrors\":null},\"dataBody\":null}");

        assertThatThrownBy(() -> skuQueryPort.findSkus(List.of(9004L)))
            .isInstanceOfSatisfying(CartException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(CartErrorCode.CART_PRODUCT_SERVICE_UNAVAILABLE));
    }
}
