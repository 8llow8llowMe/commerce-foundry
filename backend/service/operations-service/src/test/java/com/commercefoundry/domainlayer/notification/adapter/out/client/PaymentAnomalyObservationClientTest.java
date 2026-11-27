package com.commercefoundry.domainlayer.notification.adapter.out.client;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyObservationPort;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyObservationProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyObservation;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.support.TestServiceTokens;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {"operations.jobs.worker.enabled=false", "operations.jobs.planner.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:payment-anomaly-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class PaymentAnomalyObservationClientTest {
    private static final String PATH = "/internal/v1/payment-observations/anomalies";
    private static final String SENTINEL = "SENTINEL_PRIVATE_HTTP_BODY_URL_TOKEN";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final WireMockServer SERVER = start();
    private static final List<String> LANES = List.of("unknown", "manualReviewUnknown", "inProgress", "returnedReady", "lateApproved", "doneOrderUnsynced");
    /** 누락·JSON null 은 미관측으로 받는다. 객체면 다른 버킷과 같은 엄격 검증이다. */
    private static final String AUTO_CANCEL = "autoCancelManualReview";
    @Autowired PaymentAnomalyObservationPort source;
    @Autowired PaymentAnomalyObservationProcessor processor;
    @Autowired CircuitBreakerRegistry circuits;
    @Autowired Environment environment;

    private static WireMockServer start() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }
    @DynamicPropertySource
    static void url(DynamicPropertyRegistry properties) {
        properties.add("spring.cloud.openfeign.client.config.paymentAnomalyObservationClient.url", SERVER::baseUrl);
    }
    @BeforeEach
    void reset() {
        SERVER.resetAll();
        circuits.circuitBreaker("payment-service").reset();
    }
    @AfterAll
    static void close() { SERVER.stop(); }

    static String valid() {
        return """
            {"dataHeader":{"success":true},"dataBody":{"observedAt":"2026-10-01T00:00:00.123456Z",
            "unknown":{"count":7,"oldestAt":"2026-09-30T23:50:00Z"},
            "manualReviewUnknown":{"count":2,"oldestAt":"2026-09-30T23:51:00Z"},
            "inProgress":{"count":3,"oldestAt":"2026-09-30T23:52:00Z"},
            "returnedReady":{"count":4,"oldestAt":"2026-09-30T23:53:00Z"},
            "lateApproved":{"count":5,"oldestAt":"2026-09-30T23:54:00Z"},
            "doneOrderUnsynced":{"count":6,"oldestAt":"2026-09-30T23:55:00Z"},
            "autoCancelManualReview":{"count":8,"oldestAt":"2026-09-30T23:56:00Z"}}}
            """;
    }

    static String changed(String path, String replacement) {
        ObjectNode root = (ObjectNode) JSON.readTree(valid());
        var parts = path.split("/");
        ObjectNode parent = root;
        for (int index = 0; index < parts.length - 1; index++) {
            parent = (ObjectNode) parent.path(parts[index]);
        }
        if (replacement == null) { parent.remove(parts[parts.length - 1]); }
        else { parent.set(parts[parts.length - 1], JSON.readTree(replacement)); }
        return root.toString();
    }

    static Stream<Arguments> invalidBodies() {
        var cases = new ArrayList<Arguments>();
        for (String path : List.of("dataHeader", "dataBody")) {
            for (String invalid : new String[] {null, "null", "[]", "1", "true", "\"object\""}) {
                cases.add(Arguments.of(path + "=" + invalid, changed(path, invalid)));
            }
        }
        for (String invalid : new String[] {null, "null", "false", "1", "\"true\"", "{}", "[]"}) {
            cases.add(Arguments.of("success=" + invalid, changed("dataHeader/success", invalid)));
        }
        for (String lane : LANES) {
            String path = "dataBody/" + lane;
            for (String invalid : new String[] {null, "null", "[]", "1", "true", "\"bucket\""}) {
                cases.add(Arguments.of(lane + "=" + invalid, changed(path, invalid)));
            }
            for (String invalid : new String[] {null, "null", "-1", "1.0", "1.5", "1e0", "true", "\"1\"", "{}", "[]", "9223372036854775808", "0"}) {
                cases.add(Arguments.of(lane + ".count=" + invalid, changed(path + "/count", invalid)));
            }
            for (String invalid : new String[] {null, "null", "1", "true", "{}", "[]", "\"bad\"", "\"2026-10-02T00:00:00Z\"",
                "\"2026-09-30T23:55:00.123456789Z\""}) {
                cases.add(Arguments.of(lane + ".oldestAt=" + invalid, changed(path + "/oldestAt", invalid)));
            }
        }
        for (String invalid : new String[] {null, "null", "1", "true", "{}", "[]", "\"bad\"", "\"2026-10-01T00:00:00.123456789Z\""}) {
            cases.add(Arguments.of("observedAt=" + invalid, changed("dataBody/observedAt", invalid)));
        }
        // 7번째 버킷: 누락·null 은 정상(미관측)이라 여기 없다. 그 밖의 비객체와 잘못된 count/oldestAt 은 관측 전체 실패다.
        String autoCancel = "dataBody/" + AUTO_CANCEL;
        for (String invalid : new String[] {"[]", "1", "0", "true", "false", "\"bucket\"", "\"null\"", "{}"}) {
            cases.add(Arguments.of(AUTO_CANCEL + "=" + invalid, changed(autoCancel, invalid)));
        }
        for (String invalid : new String[] {null, "null", "-1", "1.0", "1.5", "1e0", "true", "\"1\"", "{}", "[]", "9223372036854775808", "0"}) {
            cases.add(Arguments.of(AUTO_CANCEL + ".count=" + invalid, changed(autoCancel + "/count", invalid)));
        }
        for (String invalid : new String[] {null, "null", "1", "true", "{}", "[]", "\"bad\"", "\"2026-10-02T00:00:00Z\"",
            "\"2026-09-30T23:56:00.123456789Z\""}) {
            cases.add(Arguments.of(AUTO_CANCEL + ".oldestAt=" + invalid, changed(autoCancel + "/oldestAt", invalid)));
        }
        cases.add(Arguments.of(AUTO_CANCEL + " zero with oldestAt", changed(autoCancel, "{\"count\":0,\"oldestAt\":\"2026-09-30T23:56:00Z\"}")));
        cases.add(Arguments.of(AUTO_CANCEL + " sentinel string", changed(autoCancel, "\"" + SENTINEL + "\"")));
        cases.add(Arguments.of("manual greater than unknown", changed("dataBody/manualReviewUnknown/count", "8")));
        cases.add(Arguments.of("manual minimum before unknown", changed("dataBody/manualReviewUnknown/oldestAt", "\"2026-09-30T23:49:00Z\"")));
        cases.add(Arguments.of("equal positive count but unequal minimum", changed("dataBody/manualReviewUnknown/count", "7")));
        cases.add(Arguments.of("broken JSON", "{broken " + SENTINEL));
        cases.add(Arguments.of("root null", "null"));
        cases.add(Arguments.of("root array", "[]"));
        return cases.stream();
    }

    @Test
    void scopedServiceTokenPathAndSixDistinctFactsReachProcessor() {
        respond(valid());
        var result = processor.observe();
        assertThat(result.observedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00.123456Z"));
        assertThat(List.of(result.unknown().count(), result.manualReviewUnknown().count(), result.inProgress().count(), result.returnedReady().count(),
            result.lateApproved().count(), result.doneOrderUnsynced().count())).containsExactly(7L, 2L, 3L, 4L, 5L, 6L);
        assertThat(List.of(result.unknown().oldestAt(), result.manualReviewUnknown().oldestAt(), result.inProgress().oldestAt(), result.returnedReady().oldestAt(),
            result.lateApproved().oldestAt(), result.doneOrderUnsynced().oldestAt()))
            .containsExactly(Instant.parse("2026-09-30T23:50:00Z"), Instant.parse("2026-09-30T23:51:00Z"), Instant.parse("2026-09-30T23:52:00Z"),
                Instant.parse("2026-09-30T23:53:00Z"), Instant.parse("2026-09-30T23:54:00Z"), Instant.parse("2026-09-30T23:55:00Z"));
        assertThat(result.autoCancelManualReview()).isEqualTo(new PaymentAnomalyObservation.Fact(8, Instant.parse("2026-09-30T23:56:00Z")));
        var requests = SERVER.findAll(getRequestedFor(urlEqualTo(PATH)));
        assertThat(requests).hasSize(1);
        var token = requests.getFirst().getHeader("Authorization");
        var jwt = InternalTokenDecoderFactory.create(TestServiceTokens.properties("payment-service"), Clock.systemUTC()).decode(token.substring(7));
        assertThat(jwt.getAudience()).containsExactly("payment-service");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("operations-service");
        assertThat(environment.getProperty("spring.cloud.openfeign.client.config.default.connect-timeout")).isEqualTo("2000");
        assertThat(environment.getProperty("spring.cloud.openfeign.client.config.default.read-timeout")).isEqualTo("3000");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void anyInvalidLaneRejectsWholeResponseWithoutOriginalCause(String description, String body, CapturedOutput output) {
        respond(body);
        unavailable();
        SERVER.verify(1, getRequestedFor(urlEqualTo(PATH)));
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }

    @Test
    void explicitZeroNullEqualSubsetMaxLongAndAdditionalFieldsAreAccepted() {
        respond(valid().replace("\"count\":7", "\"count\":9223372036854775807").replace("\"count\":2", "\"count\":9223372036854775807")
            .replace("23:51:00Z", "23:50:00Z").replace("\"success\":true", "\"success\":true,\"futureHeader\":[]"));
        assertThat(source.observe().manualReviewUnknown().count()).isEqualTo(Long.MAX_VALUE);
        String zero = valid().replaceAll("\"count\":[0-9]+", "\"count\":0")
            .replaceAll("\"oldestAt\":\"[^\"]+\"", "\"oldestAt\":null");
        respond(zero);
        assertThat(source.observe().unknown().oldestAt()).isNull();
        respond(changed("dataBody/futureField", "{\"count\":\"ignored\"}"));
        assertThat(source.observe().doneOrderUnsynced().count()).isEqualTo(6);
    }

    @Test
    void autoCancelBucketMissingOrNullIsUnobservedAndZeroOrIndependentValuesAreObserved(CapturedOutput output) {
        // 구버전 payment(필드 없음)와 자동 취소 게이트 OFF(JSON null)는 0건과 다른 미관측이다. 나머지 여섯 버킷은 그대로 읽는다.
        for (String body : List.of(changed("dataBody/" + AUTO_CANCEL, null), changed("dataBody/" + AUTO_CANCEL, "null"))) {
            respond(body);
            var unobserved = source.observe();
            assertThat(unobserved.autoCancelManualReview()).isNull();
            assertThat(unobserved.doneOrderUnsynced().count()).isEqualTo(6);
            SERVER.resetAll();
        }
        respond(changed("dataBody/" + AUTO_CANCEL, "{\"count\":0,\"oldestAt\":null}"));
        assertThat(source.observe().autoCancelManualReview()).isEqualTo(new PaymentAnomalyObservation.Fact(0, null));
        // UNKNOWN 수동 확인 부분집합과 무관하다 — 수동 확인 0건이어도, unknown 보다 많고 더 오래돼도 받는다. 추가 필드는 무시한다.
        respond(changed("dataBody/" + AUTO_CANCEL, "{\"count\":9223372036854775807,\"oldestAt\":\"2026-01-01T00:00:00.000001Z\",\"reason\":\""
            + SENTINEL + "\"}").replace("\"count\":2,", "\"count\":0,").replace("\"2026-09-30T23:51:00Z\"", "null"));
        var independent = source.observe();
        assertThat(independent.manualReviewUnknown()).isEqualTo(new PaymentAnomalyObservation.Fact(0, null));
        assertThat(independent.autoCancelManualReview()).isEqualTo(new PaymentAnomalyObservation.Fact(Long.MAX_VALUE, Instant.parse("2026-01-01T00:00:00.000001Z")));
        respond(changed("dataBody/" + AUTO_CANCEL, "{\"count\":1,\"oldestAt\":\"2026-10-01T00:00:00.123456Z\"}"));
        assertThat(source.observe().autoCancelManualReview().oldestAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00.123456Z"));
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }

    @Test
    void remoteErrorsAndOpenCircuitAreSanitizedWithoutRetries(CapturedOutput output) {
        for (int status : List.of(401, 403, 500, 503)) {
            SERVER.resetAll();
            SERVER.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status).withBody(SENTINEL)));
            unavailable();
            SERVER.verify(1, getRequestedFor(urlEqualTo(PATH)));
        }
        circuits.circuitBreaker("payment-service").transitionToOpenState();
        SERVER.resetRequests();
        unavailable();
        SERVER.verify(0, getRequestedFor(urlEqualTo(PATH)));
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }

    @Test
    void actualDefaultReadTimeoutDoesNotRetry(CapturedOutput output) {
        SERVER.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withHeader("Content-Type", "application/json")
            .withFixedDelay(4500).withBody(valid())));
        long started = System.nanoTime();
        unavailable();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(2800)).isLessThan(Duration.ofSeconds(8));
        SERVER.verify(1, getRequestedFor(urlEqualTo(PATH)));
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }

    private void respond(String body) {
        SERVER.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }
    private void unavailable() {
        assertThatThrownBy(source::observe).isInstanceOfSatisfying(NotificationException.class, exception -> {
            assertThat(exception.getErrorCode().getCode()).isEqualTo("NOTIFICATION_052");
            assertThat(exception.getMessage()).isEqualTo("결제 이상 상태를 확인할 수 없습니다.");
            assertThat(exception.getCause()).isNull();
            assertThat(exception.toString()).doesNotContain(SENTINEL);
        });
    }
}
