package com.commercefoundry.domainlayer.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 결제 준비 → 승인을 실제 컨텍스트 · PostgreSQL · 가짜 토스 · 가짜 order-service(WireMock)로 끝까지 돌린다 ({@code payment-guide.md} §11 — UNKNOWN · 중복 승인 경로).
 * 트랜잭션 경계(IN_PROGRESS 커밋 → PG → 결과 커밋) · 버전 충돌 · 유니크 키는 목으로 검증되지 않아 여기서 본다.
 * Docker 없는 로컬은 건너뛰고 CI 는 Docker 가 없으면 실패한다 ({@link EnabledWithDockerOrOnCi}). 값은 모두 테스트 전용이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=payment",
    // 운영 결제 게이트는 기본 닫힘이다 — 흐름을 보려고 연다
    "payment.enabled=true"
})
class PaymentConfirmWebIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");

    private static final String CONFIRM_PATH = "/v1/payments/confirm";
    private static final int TOSS_READ_TIMEOUT_MILLIS = 1_500;
    private static final long AMOUNT = 129_000;
    private static final AtomicLong ORDER_IDS = new AtomicLong(4_611_686_018_427_400_000L);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static WireMockServer toss;
    private static WireMockServer order;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startServers() {
        toss = new WireMockServer(wireMockConfig().dynamicPort());
        toss.start();
        order = new WireMockServer(wireMockConfig().dynamicPort());
        order.start();
    }

    @AfterAll
    static void stopServers() {
        toss.stop();
        order.stop();
    }

    /** 공급자는 컨텍스트를 만들 때 불린다 — @BeforeAll 에서 띄운 서버 주소를 읽는다. */
    @DynamicPropertySource
    static void remoteProperties(DynamicPropertyRegistry registry) {
        registry.add("toss.base-url", () -> toss.baseUrl());
        registry.add("toss.secret-key", () -> "test_sk_dummyIntegrationOnly0001");
        registry.add("spring.cloud.openfeign.client.config.toss-payments.read-timeout", () -> TOSS_READ_TIMEOUT_MILLIS);
        registry.add("spring.cloud.openfeign.client.config.orderPaymentSummaryClient.url", () -> order.baseUrl());
    }

    @BeforeEach
    void reset() {
        toss.resetAll();
        order.resetAll();
    }

    private static String bearer() {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
    }

    /** 결제 대기 주문 하나를 order-service 스텁에 올리고 아이디를 돌려준다. */
    private static long pendingOrder() {
        long orderId = ORDER_IDS.incrementAndGet();
        String body = """
            {"dataHeader":{"success":true,"resultCode":null,"resultMessage":null,"fieldErrors":null},
             "dataBody":{"orderId":"%d","memberId":"%d","status":"PENDING_PAYMENT","totalAmount":{"amount":%d,"currency":"KRW"},
               "orderName":"테스트 저고리 외 1건","paymentDueAt":"%s"}}
            """.formatted(orderId, TestTokens.MEMBER_ID, AMOUNT, Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS));
        order.stubFor(get(urlPathEqualTo("/internal/v1/orders/" + orderId + "/payment-summary"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
        return orderId;
    }

    /** 결제 준비 → 201. pgOrderId 를 돌려준다. */
    private String prepare(long orderId) {
        MvcTestResult result = mvc.post().uri("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"" + orderId + "\"}").exchange();
        assertThat(result).hasStatus(201);
        return body(result).path("dataBody").path("pgOrderId").asString();
    }

    private MvcTestResult confirm(UUID key, String pgOrderId, String paymentKey) {
        String body = "{\"paymentKey\":\"%s\",\"pgOrderId\":\"%s\",\"amount\":{\"amount\":%d,\"currency\":\"KRW\"}}".formatted(paymentKey, pgOrderId, AMOUNT);
        return mvc.post().uri("/api/v1/payments/confirm").header(HttpHeaders.AUTHORIZATION, bearer())
            .header("Idempotency-Key", key.toString()).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static JsonNode body(MvcTestResult result) {
        try {
            return JSON.readTree(result.getResponse().getContentAsString());
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String statusOf(MvcTestResult result) {
        return body(result).path("dataBody").path("status").path("code").asString();
    }

    private static String codeOf(MvcTestResult result) {
        return body(result).path("dataHeader").path("resultCode").asString();
    }

    private long versionOf(String pgOrderId) {
        return jdbcTemplate.queryForObject("select version from payment.payment where pg_order_id = ?", Long.class, pgOrderId);
    }

    private String dbStatusOf(String pgOrderId) {
        return jdbcTemplate.queryForObject("select status from payment.payment where pg_order_id = ?", String.class, pgOrderId);
    }

    private static String paymentKey(String pgOrderId) {
        return "tgen_it_" + pgOrderId;
    }

    private static ResponseDefinitionBuilder tossDone(String pgOrderId) {
        return tossJson(200, """
            {"paymentKey":"%s","orderId":"%s","orderName":"테스트","currency":"KRW","method":"카드","totalAmount":%d,"balanceAmount":%d,
             "status":"DONE","requestedAt":"2026-10-21T10:00:00+09:00","approvedAt":"2026-10-21T10:00:03+09:00","easyPay":null,"failure":null}
            """.formatted(paymentKey(pgOrderId), pgOrderId, AMOUNT, AMOUNT));
    }

    private static ResponseDefinitionBuilder tossJson(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    @Test
    @DisplayName("준비 → 승인 — 토스 200 DONE 이면 DONE 200, 토스에는 결제 시도 아이디를 멱등 키로 한 번 보낸다")
    void approves() {
        String pgOrderId = prepare(pendingOrder());
        toss.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(tossDone(pgOrderId)));

        MvcTestResult result = confirm(UUID.randomUUID(), pgOrderId, paymentKey(pgOrderId));

        assertThat(result).hasStatusOk();
        assertThat(statusOf(result)).isEqualTo("DONE");
        assertThat(dbStatusOf(pgOrderId)).isEqualTo("DONE");
        toss.verify(1, postRequestedFor(urlEqualTo(CONFIRM_PATH)).withHeader("Idempotency-Key",
            com.github.tomakehurst.wiremock.client.WireMock.equalTo(pgOrderId)));
    }

    @Test
    @DisplayName("토스 거절 → FAILED 200 과 실패 사유")
    void rejects() {
        String pgOrderId = prepare(pendingOrder());
        toss.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(tossJson(403, "{\"code\":\"REJECT_CARD_COMPANY\",\"message\":\"카드사 거절\"}")));

        MvcTestResult result = confirm(UUID.randomUUID(), pgOrderId, paymentKey(pgOrderId));

        assertThat(result).hasStatusOk();
        assertThat(statusOf(result)).isEqualTo("FAILED");
        assertThat(body(result).path("dataBody").path("failure").path("code").asString()).isEqualTo("REJECT_CARD_COMPANY");
    }

    @Test
    @DisplayName("토스 응답 타임아웃 → UNKNOWN 200 (실패가 아니다)")
    void timeoutIsUnknown() {
        String pgOrderId = prepare(pendingOrder());
        toss.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(tossDone(pgOrderId).withFixedDelay(TOSS_READ_TIMEOUT_MILLIS * 2)));

        MvcTestResult result = confirm(UUID.randomUUID(), pgOrderId, paymentKey(pgOrderId));

        assertThat(result).hasStatusOk();
        assertThat(statusOf(result)).isEqualTo("UNKNOWN");
        assertThat(dbStatusOf(pgOrderId)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("보내지 못함(키 설정 오류 401) → READY 로 되돌리고 503 PAYMENT_010 + Retry-After. 같은 본문 재시도는 DONE")
    void notSentThenRetry() {
        String pgOrderId = prepare(pendingOrder());
        toss.stubFor(post(urlEqualTo(CONFIRM_PATH)).inScenario("retry").whenScenarioStateIs(Scenario.STARTED)
            .willReturn(tossJson(401, "{\"code\":\"UNAUTHORIZED_KEY\",\"message\":\"키 오류\"}")).willSetStateTo("fixed"));
        toss.stubFor(post(urlEqualTo(CONFIRM_PATH)).inScenario("retry").whenScenarioStateIs("fixed").willReturn(tossDone(pgOrderId)));
        UUID key = UUID.randomUUID();

        MvcTestResult first = confirm(key, pgOrderId, paymentKey(pgOrderId));

        assertThat(first).hasStatus(503);
        assertThat(codeOf(first)).isEqualTo("PAYMENT_010");
        assertThat(first).headers().hasValue(HttpHeaders.RETRY_AFTER, "5");
        assertThat(dbStatusOf(pgOrderId)).isEqualTo("READY");

        MvcTestResult retried = confirm(key, pgOrderId, paymentKey(pgOrderId));

        assertThat(retried).hasStatusOk();
        assertThat(statusOf(retried)).isEqualTo("DONE");
        toss.verify(2, postRequestedFor(urlEqualTo(CONFIRM_PATH)));
    }

    @Test
    @DisplayName("동시 승인 2건 — 토스 승인 요청은 한 번이다. 하나는 DONE 200, 다른 하나는 처리 중 409 PAYMENT_007 (또는 끝난 뒤면 DONE)")
    void concurrentConfirmsCallTossOnce() throws Exception {
        String pgOrderId = prepare(pendingOrder());
        toss.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(tossDone(pgOrderId).withFixedDelay(500)));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcTestResult>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                UUID key = UUID.randomUUID();
                Callable<MvcTestResult> call = () -> {
                    start.await();
                    return confirm(key, pgOrderId, paymentKey(pgOrderId));
                };
                futures.add(executor.submit(call));
            }
            start.countDown();
            List<MvcTestResult> results = new ArrayList<>();
            for (Future<MvcTestResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 200)
                .allSatisfy(result -> assertThat(statusOf(result)).isEqualTo("DONE")).isNotEmpty();
            assertThat(results).filteredOn(result -> result.getResponse().getStatus() != 200)
                .allSatisfy(result -> assertThat(codeOf(result)).isEqualTo("PAYMENT_007"));
        } finally {
            executor.shutdownNow();
        }
        toss.verify(1, postRequestedFor(urlEqualTo(CONFIRM_PATH)));
        assertThat(dbStatusOf(pgOrderId)).isEqualTo("DONE");
    }

    @Test
    @DisplayName("같은 키의 재전송은 처음 결과 200 그대로 — 저장하지 않아 버전이 그대로이고 토스를 다시 부르지 않는다")
    void sameKeyReplayDoesNotWrite() {
        String pgOrderId = prepare(pendingOrder());
        toss.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(tossDone(pgOrderId)));
        UUID key = UUID.randomUUID();
        assertThat(statusOf(confirm(key, pgOrderId, paymentKey(pgOrderId)))).isEqualTo("DONE");
        long version = versionOf(pgOrderId);

        MvcTestResult replay = confirm(key, pgOrderId, paymentKey(pgOrderId));

        assertThat(replay).hasStatusOk();
        assertThat(statusOf(replay)).isEqualTo("DONE");
        assertThat(versionOf(pgOrderId)).isEqualTo(version);
        toss.verify(1, postRequestedFor(urlEqualTo(CONFIRM_PATH)));
    }

    @Test
    @DisplayName("같은 키로 다른 결제를 승인하려 하면 409 PAYMENT_014 — 토스를 부르지 않는다")
    void sameKeyOtherPaymentConflicts() {
        String first = prepare(pendingOrder());
        String second = prepare(pendingOrder());
        toss.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(tossDone(first)));
        UUID key = UUID.randomUUID();
        assertThat(statusOf(confirm(key, first, paymentKey(first)))).isEqualTo("DONE");

        MvcTestResult reused = confirm(key, second, paymentKey(second));

        assertThat(reused).hasStatus(409);
        assertThat(codeOf(reused)).isEqualTo("PAYMENT_014");
        assertThat(dbStatusOf(second)).isEqualTo("READY");
        toss.verify(1, postRequestedFor(urlEqualTo(CONFIRM_PATH)));
    }

    @Test
    @DisplayName("승인 직전 주문을 다시 확인한다 — 준비 뒤 주문이 취소됐으면 409 PAYMENT_003 이고 토스를 부르지 않으며 결제 시도는 READY 그대로다")
    void confirmRechecksOrder() {
        long orderId = pendingOrder();
        String pgOrderId = prepare(orderId);
        String canceled = """
            {"dataHeader":{"success":true,"resultCode":null,"resultMessage":null,"fieldErrors":null},
             "dataBody":{"orderId":"%d","memberId":"%d","status":"CANCELED","totalAmount":{"amount":%d,"currency":"KRW"},
               "orderName":"테스트 저고리 외 1건","paymentDueAt":"%s"}}
            """.formatted(orderId, TestTokens.MEMBER_ID, AMOUNT, Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS));
        order.stubFor(get(urlPathEqualTo("/internal/v1/orders/" + orderId + "/payment-summary"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(canceled)));
        toss.stubFor(post(urlEqualTo(CONFIRM_PATH)).willReturn(tossDone(pgOrderId)));

        MvcTestResult result = confirm(UUID.randomUUID(), pgOrderId, paymentKey(pgOrderId));

        assertThat(result).hasStatus(409);
        assertThat(codeOf(result)).isEqualTo("PAYMENT_003");
        assertThat(dbStatusOf(pgOrderId)).isEqualTo("READY");
        toss.verify(0, postRequestedFor(urlEqualTo(CONFIRM_PATH)));
    }

    @Test
    @DisplayName("같은 주문의 결제 준비를 다시 부르면 같은 결제 시도를 200 으로 돌려준다")
    void prepareIsIdempotentPerOrder() {
        long orderId = pendingOrder();
        String pgOrderId = prepare(orderId);

        MvcTestResult again = mvc.post().uri("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"" + orderId + "\"}").exchange();

        assertThat(again).hasStatusOk();
        assertThat(body(again).path("dataBody").path("pgOrderId").asString()).isEqualTo(pgOrderId);
    }
}
