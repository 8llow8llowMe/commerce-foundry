package com.commercefoundry.domainlayer.settlement.adapter.out.client.pg.toss;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.settlement.application.exception.SettlementCollectionException;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementCollectionException.Failure;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementGatewayPort;
import com.commercefoundry.domainlayer.settlement.domain.model.PgSettlementItem;
import com.commercefoundry.support.TossWireMockSupport;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.LocalDate;
import java.util.List;
import java.util.StringJoiner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

/**
 * 토스 정산 조회 어댑터를 실제 Feign 클라이언트 · yml 설정(toss-settlements)으로 가짜 토스 서버에 붙인 계약 시험 (#219, {@code payment-guide.md} §10).
 * 애노테이션 · 상위 클래스가 결제 승인 어댑터 시험과 같아 Spring 컨텍스트를 같이 쓴다({@link TossWireMockSupport}). 결제키 · 거래키는 합성 값이다.
 *
 * <p>정산 클라이언트의 read timeout 은 기동 가드가 60초 미만을 막아 여기서 줄이지 않는다 — 타임아웃은 같은 IOException 경로인 연결 끊김으로 재현하고,
 * 타임아웃 예외 자체의 분류는 단위 시험({@link TossSettlementGatewayAdapterTest})이 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class TossSettlementGatewayAdapterWireMockTest extends TossWireMockSupport {

    private static final LocalDate SOLD_DATE = LocalDate.parse("2026-11-20");
    private static final String PATH = "/v1/settlements";
    private static final String PAYMENT_KEY = "tgen_wiremock_settle_0001";

    @Autowired
    private SettlementGatewayPort gateway;

    @BeforeEach
    void reset() {
        TOSS.resetAll();
    }

    @Test
    @DisplayName("한 페이지 — 쿼리(startDate · endDate · dateType=soldDate · page=1 · size=5000)와 Basic 인증을 싣고, 받은 건수 < size 면 끝낸다")
    void singlePage() {
        stubPage(1, okJson(array(item("tx-1", "129000", null), item("tx-2", "-29000", null))));

        List<PgSettlementItem> items = gateway.fetchSettlements(SOLD_DATE);

        assertThat(items).extracting(PgSettlementItem::transactionKey).containsExactly("tx-1", "tx-2");
        TOSS.verify(1, getRequestedFor(urlPathEqualTo(PATH))
            .withQueryParam("startDate", equalTo("2026-11-20"))
            .withQueryParam("endDate", equalTo("2026-11-20"))
            .withQueryParam("dateType", equalTo("soldDate"))
            .withQueryParam("page", equalTo("1"))
            .withQueryParam("size", equalTo("5000"))
            .withHeader("Authorization", equalTo(EXPECTED_AUTHORIZATION)));
    }

    @Test
    @DisplayName("여러 페이지 — 정확히 size 건이면 다음 페이지를 부르고, 받은 건수 < size 인 페이지에서 멈춘다")
    void multiplePagesUntilShortPage() {
        stubPage(1, okJson(manyItems("p1-", TossSettlementGatewayAdapter.PAGE_SIZE)));
        stubPage(2, okJson(manyItems("p2-", 1)));

        List<PgSettlementItem> items = gateway.fetchSettlements(SOLD_DATE);

        assertThat(items).hasSize(TossSettlementGatewayAdapter.PAGE_SIZE + 1);
        TOSS.verify(1, getRequestedFor(urlPathEqualTo(PATH)).withQueryParam("page", equalTo("1")));
        TOSS.verify(1, getRequestedFor(urlPathEqualTo(PATH)).withQueryParam("page", equalTo("2")));
        TOSS.verify(0, getRequestedFor(urlPathEqualTo(PATH)).withQueryParam("page", equalTo("3")));
    }

    @Test
    @DisplayName("size 경계 — 첫 페이지가 정확히 size 건이고 다음 페이지가 비면 두 번 부르고 끝낸다")
    void exactPageThenEmpty() {
        stubPage(1, okJson(manyItems("p1-", TossSettlementGatewayAdapter.PAGE_SIZE)));
        stubPage(2, okJson("[]"));

        assertThat(gateway.fetchSettlements(SOLD_DATE)).hasSize(TossSettlementGatewayAdapter.PAGE_SIZE);
        TOSS.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    // 미확인 가정 — 운영 켜기 전 고객사 · 토스 확인: 취소 거래는 cancel 객체가 있거나 amount 가 음수다. 양수 + cancel 객체도 음수로 정규화한다
    @Test
    @DisplayName("취소 거래 — 음수 금액 · 양수 금액 + cancel 객체 모두 음수로 정규화한다")
    void normalizesCancelItems() {
        stubPage(1, okJson(array(item("tx-1", "129000", null), item("tx-2", "-20000", null), item("tx-3", "9000", "{\"cancelAmount\":9000}"))));

        List<PgSettlementItem> items = gateway.fetchSettlements(SOLD_DATE);

        assertThat(items).extracting(PgSettlementItem::amount).containsExactly(129_000L, -20_000L, -9_000L);
        assertThat(items).extracting(PgSettlementItem::canceled).containsExactly(false, true, true);
    }

    @Test
    @DisplayName("429 → 성공 — Retry-After(초)를 따라 한 번 더 부른다")
    void rateLimitedThenSucceeds() {
        TOSS.stubFor(page(1).inScenario("429").whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")).willSetStateTo("ok"));
        TOSS.stubFor(page(1).inScenario("429").whenScenarioStateIs("ok").willReturn(okJson(array(item("tx-1", "129000", null)))));

        assertThat(gateway.fetchSettlements(SOLD_DATE)).hasSize(1);
        TOSS.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("429 가 세 번 재시도 뒤에도 계속되면 RATE_LIMITED — 모두 네 번 부른다")
    void rateLimitedExhausted() {
        stubPage(1, aResponse().withStatus(429).withHeader("Retry-After", "0"));

        assertFailure(Failure.RATE_LIMITED);
        TOSS.verify(4, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("5xx — 다시 부르지 않고 UNAVAILABLE")
    void serverErrorNotRetried() {
        stubPage(1, aResponse().withStatus(503).withHeader("Content-Type", "application/json").withBody("{\"code\":\"PROVIDER_ERROR\"}"));

        assertFailure(Failure.UNAVAILABLE);
        TOSS.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("연결 끊김(타임아웃과 같은 IOException 경로) — 다시 부르지 않고 UNAVAILABLE")
    void transportFailureNotRetried() {
        stubPage(1, aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

        assertFailure(Failure.UNAVAILABLE);
        TOSS.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    // 미확인 가정 — 운영 켜기 전 고객사 · 토스 확인: 최상위는 JSON 배열이다. 래퍼 객체면 파싱 메서드만 바꾼다
    @Test
    @DisplayName("최상위가 배열이 아니면 계약 위반 — 부분 결과 없이 실패한다")
    void wrapperObjectIsContractViolation() {
        stubPage(1, okJson("{\"data\":" + array(item("tx-1", "129000", null)) + "}"));

        assertFailure(Failure.CONTRACT_VIOLATION);
    }

    @Test
    @DisplayName("로그에 시크릿 키 · Authorization · 결제키를 남기지 않는다")
    void logsDoNotLeakSecrets(CapturedOutput output) {
        stubPage(1, aResponse().withStatus(401).withHeader("Content-Type", "application/json")
            .withBody("{\"code\":\"UNAUTHORIZED_KEY\",\"message\":\"" + PAYMENT_KEY + "\"}"));

        assertFailure(Failure.REJECTED);
        assertThat(output.getAll()).doesNotContain(SECRET_KEY).doesNotContain(EXPECTED_AUTHORIZATION).doesNotContain(PAYMENT_KEY);
    }

    private void assertFailure(Failure failure) {
        assertThatThrownBy(() -> gateway.fetchSettlements(SOLD_DATE)).isInstanceOfSatisfying(SettlementCollectionException.class,
            exception -> assertThat(exception.getFailure()).isEqualTo(failure));
    }

    private static MappingBuilder page(int page) {
        return get(urlPathEqualTo(PATH)).withQueryParam("page", equalTo(Integer.toString(page)));
    }

    private static void stubPage(int page, ResponseDefinitionBuilder response) {
        TOSS.stubFor(page(page).willReturn(response));
    }

    private static ResponseDefinitionBuilder okJson(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String array(String... items) {
        return "[" + String.join(",", items) + "]";
    }

    private static String manyItems(String prefix, int count) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (int index = 0; index < count; index++) {
            joiner.add(item(prefix + index, "1000", null));
        }
        return joiner.toString();
    }

    /** 토스 Settlement 항목을 흉내 낸 합성 JSON. 읽지 않는 칸(mId · interestFee · vat)도 섞는다. */
    private static String item(String transactionKey, String amount, String cancel) {
        return ("{\"mId\":\"tosspayments\",\"paymentKey\":\"%s\",\"transactionKey\":\"%s\",\"orderId\":\"9000000001\",\"currency\":\"KRW\",\"method\":\"카드\","
            + "\"amount\":%s,\"interestFee\":0,\"fees\":[{\"type\":\"BASE\",\"supplyAmount\":30}],\"vat\":3,\"payOutAmount\":967,"
            + "\"approvedAt\":\"2026-11-20T10:00:03+09:00\",\"soldDate\":\"2026-11-20\",\"paidOutDate\":\"2026-11-23\",\"cancel\":%s}")
            .formatted(PAYMENT_KEY, transactionKey, amount, cancel == null ? "null" : cancel);
    }
}
