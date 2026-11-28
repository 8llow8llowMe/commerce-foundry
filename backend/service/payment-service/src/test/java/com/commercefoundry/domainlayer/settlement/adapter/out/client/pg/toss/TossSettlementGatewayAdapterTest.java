package com.commercefoundry.domainlayer.settlement.adapter.out.client.pg.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.settlement.application.exception.SettlementCollectionException;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementCollectionException.Failure;
import com.commercefoundry.domainlayer.settlement.domain.model.PgSettlementItem;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementPaymentMethod;
import com.commercefoundry.global.properties.TossPaymentsProperties;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.codec.ErrorDecoder;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 정산 어댑터의 429 백오프 · 실패 분류 · 응답 해석 — 가짜 클라이언트와 대기를 기록만 하는 sleeper 로 본다. HTTP 계약은 {@link TossSettlementGatewayAdapterWireMockTest}.
 * 키 · 결제키 · 거래키는 형식만 흉내 낸 합성 값이다.
 */
class TossSettlementGatewayAdapterTest {

    static final LocalDate SOLD_DATE = LocalDate.parse("2026-11-20");
    static final String METHOD_KEY = "TossSettlementsClient#getSettlements";

    final FakeClient client = new FakeClient();
    final List<Duration> sleeps = new ArrayList<>();
    final TossSettlementGatewayAdapter adapter = new TossSettlementGatewayAdapter(client,
        new TossPaymentsProperties("test_sk_dummySettlementUnit0001", "http://127.0.0.1:9"), JsonMapper.builder().build(), sleeps::add);

    @Nested
    @DisplayName("429 제한 백오프")
    class RateLimit {

        @Test
        @DisplayName("Retry-After 가 없으면 1s → 2s → 4s 로 세 번까지 다시 부르고 성공하면 그 결과다")
        void exponentialBackoffThenSuccess() {
            client.enqueue(error(429, Map.of()), error(429, Map.of()), error(429, Map.of()), body("[" + item("tx-1", "tgen_unit_0001", "129000", null) + "]"));

            List<PgSettlementItem> items = adapter.fetchSettlements(SOLD_DATE);

            assertThat(items).hasSize(1);
            assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
            assertThat(client.calls).isEqualTo(4);
        }

        @Test
        @DisplayName("세 번 다시 불러도 429 면 RATE_LIMITED — 네 번째 대기는 없다")
        void exhausted() {
            client.enqueue(error(429, Map.of()), error(429, Map.of()), error(429, Map.of()), error(429, Map.of()));

            assertFailure(Failure.RATE_LIMITED);
            assertThat(client.calls).isEqualTo(4);
            assertThat(sleeps).hasSize(3);
        }

        @ParameterizedTest
        @CsvSource({"7, 7", "0, 0", "120, 30", "'Wed, 21 Oct 2015 07:28:00 GMT', 1", "-3, 1"})
        @DisplayName("Retry-After(초)가 있으면 그 값 · 상한 30s, 숫자가 아니면 지수 백오프")
        void retryAfterHeader(String header, long expectedSeconds) {
            client.enqueue(error(429, Map.of("Retry-After", List.of(header))), body("[]"));

            adapter.fetchSettlements(SOLD_DATE);

            assertThat(sleeps).containsExactly(Duration.ofSeconds(expectedSeconds));
        }
    }

    @Nested
    @DisplayName("재시도하지 않는 실패")
    class NoRetry {

        @Test
        @DisplayName("5xx — UNAVAILABLE, 한 번만 부른다")
        void serverError() {
            client.enqueue(error(503, Map.of()));

            assertFailure(Failure.UNAVAILABLE);
            assertThat(client.calls).isEqualTo(1);
            assertThat(sleeps).isEmpty();
        }

        @Test
        @DisplayName("타임아웃(IOException 은 status -1) — UNAVAILABLE, 한 번만 부른다")
        void timeout() {
            client.enqueue(() -> {
                throw new RetryableException(-1, "Read timed out", Request.HttpMethod.GET, new SocketTimeoutException("Read timed out"), (Long) null,
                    request());
            });

            assertFailure(Failure.UNAVAILABLE);
            assertThat(client.calls).isEqualTo(1);
            assertThat(sleeps).isEmpty();
        }

        @Test
        @DisplayName("401(키 문제) — REJECTED, 한 번만 부른다")
        void unauthorized() {
            client.enqueue(error(401, Map.of()));

            assertFailure(Failure.REJECTED);
            assertThat(client.calls).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("응답 해석")
    class Parsing {

        @Test
        @DisplayName("승인 · 취소(음수) · 취소(양수 + cancel 객체) — 취소는 언제나 음수로 정규화한다")
        void normalizesCancelSign() {
            client.enqueue(body("[" + item("tx-1", "tgen_unit_0001", "129000", null) + "," + item("tx-2", "tgen_unit_0001", "-29000", null) + ","
                + item("tx-3", "tgen_unit_0001", "1000", "{\"cancelAmount\":1000}") + "]"));

            List<PgSettlementItem> items = adapter.fetchSettlements(SOLD_DATE);

            assertThat(items).extracting(PgSettlementItem::amount).containsExactly(129_000L, -29_000L, -1_000L);
            assertThat(items).extracting(PgSettlementItem::canceled).containsExactly(false, true, true);
        }

        @Test
        @DisplayName("수수료 합 = fees 공급가 합 · 지급 금액 · 결제수단 · 승인 시각 · 지급일을 옮긴다")
        void mapsFields() {
            client.enqueue(body("[" + item("tx-1", "tgen_unit_0001", "129000", null) + "]"));

            PgSettlementItem item = adapter.fetchSettlements(SOLD_DATE).getFirst();

            assertThat(item.feeAmount()).isEqualTo(3_000);
            assertThat(item.payOutAmount()).isEqualTo(125_700);
            assertThat(item.method()).isEqualTo(SettlementPaymentMethod.CARD);
            assertThat(item.approvedAt()).isEqualTo(Instant.parse("2026-11-20T01:00:03Z"));
            assertThat(item.paidOutDate()).isEqualTo(LocalDate.parse("2026-11-23"));
            assertThat(item.pgOrderId()).isEqualTo("9000000001");
        }

        @Test
        @DisplayName("같은 거래키가 같은 내용으로 두 번 오면 하나로 합친다")
        void mergesIdenticalDuplicates() {
            String same = item("tx-1", "tgen_unit_0001", "129000", null);
            client.enqueue(body("[" + same + "," + same + "]"));

            assertThat(adapter.fetchSettlements(SOLD_DATE)).hasSize(1);
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "{\"data\":[]}",
            "not-json",
            "[{\"transactionKey\":\"tx-1\",\"currency\":\"KRW\",\"amount\":1,\"soldDate\":\"2026-11-20\"}]",
            "[{\"transactionKey\":\"tx-1\",\"paymentKey\":\"tgen_unit_0001\",\"currency\":\"KRW\",\"amount\":100.5,\"soldDate\":\"2026-11-20\"}]",
            "[{\"transactionKey\":\"tx-1\",\"paymentKey\":\"tgen_unit_0001\",\"currency\":\"KRW\",\"amount\":1,\"soldDate\":\"2026-11-21\"}]",
            "[{\"transactionKey\":\"tx-1\",\"paymentKey\":\"tgen_unit_0001\",\"currency\":\"JPY\",\"amount\":1,\"soldDate\":\"2026-11-20\"}]",
            "[{\"transactionKey\":\"tx-1\",\"paymentKey\":\"tgen_unit_0001\",\"currency\":\"KRW\",\"amount\":1,\"soldDate\":\"2026-11-20\"},"
                + "{\"transactionKey\":\"tx-1\",\"paymentKey\":\"tgen_unit_0001\",\"currency\":\"KRW\",\"amount\":2,\"soldDate\":\"2026-11-20\"}]"
        })
        @DisplayName("래퍼 객체 · 깨진 JSON · 필수 칸 없음 · 통화 자릿수 초과 · 다른 매출일 · 모르는 통화 · 다른 내용의 같은 거래키는 계약 위반")
        void contractViolations(String json) {
            client.enqueue(body(json));

            assertFailure(Failure.CONTRACT_VIOLATION);
        }
    }

    private void assertFailure(Failure failure) {
        assertThatThrownBy(() -> adapter.fetchSettlements(SOLD_DATE)).isInstanceOfSatisfying(SettlementCollectionException.class,
            exception -> assertThat(exception.getFailure()).isEqualTo(failure));
    }

    /** 토스 Settlement 항목을 흉내 낸 합성 JSON. 읽지 않는 칸(mId · interestFee · vat)도 섞어 모르는 칸을 무시하는지 같이 본다. */
    static String item(String transactionKey, String paymentKey, String amount, String cancel) {
        return """
            {"mId":"tosspayments","paymentKey":"%s","transactionKey":"%s","orderId":"9000000001","currency":"KRW","method":"카드","amount":%s,
             "interestFee":0,"fees":[{"type":"BASE","supplyAmount":2700},{"type":"INSTALLMENT_DISCOUNT","supplyAmount":300}],"supplyAmount":117273,
             "vat":11727,"payOutAmount":125700,"approvedAt":"2026-11-20T10:00:03+09:00","soldDate":"2026-11-20","paidOutDate":"2026-11-23","cancel":%s}"""
            .formatted(paymentKey, transactionKey, amount, cancel == null ? "null" : cancel);
    }

    static Supplier<byte[]> body(String json) {
        return () -> json.getBytes(StandardCharsets.UTF_8);
    }

    /** Feign 기본 ErrorDecoder 가 만드는 예외 그대로 — Retry-After 가 있으면 원인을 품은 RetryableException 이다. */
    static Supplier<byte[]> error(int status, Map<String, Collection<String>> headers) {
        return () -> {
            Response response = Response.builder().status(status).reason("synthetic").request(request()).headers(headers).body(new byte[0]).build();
            Exception decoded = new ErrorDecoder.Default().decode(METHOD_KEY, response);
            throw (FeignException) decoded;
        };
    }

    static Request request() {
        return Request.create(Request.HttpMethod.GET, "http://127.0.0.1:9/v1/settlements", Map.of(), null, StandardCharsets.UTF_8, null);
    }

    static final class FakeClient implements TossSettlementsClient {

        final Deque<Supplier<byte[]>> outcomes = new ArrayDeque<>();
        int calls;

        @SafeVarargs
        final void enqueue(Supplier<byte[]>... next) {
            outcomes.addAll(List.of(next));
        }

        @Override
        public byte[] getSettlements(String authorization, LocalDate startDate, LocalDate endDate, String dateType, int page, int size) {
            calls++;
            assertThat(authorization).startsWith("Basic ");
            assertThat(startDate).isEqualTo(SOLD_DATE);
            assertThat(endDate).isEqualTo(SOLD_DATE);
            assertThat(dateType).isEqualTo("soldDate");
            assertThat(size).isEqualTo(TossSettlementGatewayAdapter.PAGE_SIZE);
            Supplier<byte[]> outcome = outcomes.poll();
            assertThat(outcome).as("준비하지 않은 호출 page=%d", page).isNotNull();
            return outcome.get();
        }
    }
}
