package com.commercefoundry.domainlayer.aiusage.adapter.in.metering;

import static com.commercefoundry.ai.support.StubMessagesServer.blockStart;
import static com.commercefoundry.ai.support.StubMessagesServer.error;
import static com.commercefoundry.ai.support.StubMessagesServer.messageStart;
import static com.commercefoundry.ai.support.StubMessagesServer.ok;
import static com.commercefoundry.ai.support.StubMessagesServer.okToolUse;
import static com.commercefoundry.ai.support.StubMessagesServer.stream;
import static com.commercefoundry.ai.support.StubMessagesServer.textDelta;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.ai.client.AiClient;
import com.commercefoundry.ai.client.AiContent;
import com.commercefoundry.ai.client.AiConversationRequest;
import com.commercefoundry.ai.client.AiConversationResult;
import com.commercefoundry.ai.client.AiException;
import com.commercefoundry.ai.client.AiException.Reason;
import com.commercefoundry.ai.client.AiMessage;
import com.commercefoundry.ai.client.AiRequest;
import com.commercefoundry.ai.client.AiResult;
import com.commercefoundry.ai.client.AiTool;
import com.commercefoundry.ai.metering.AiCallInterceptor;
import com.commercefoundry.ai.support.StubMessagesServer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code ai.enabled=true} 로 operations 컨텍스트를 띄워 실제 {@code AiClient}(SDK) → 계량 인터셉터 → 원장(H2)을 끝에서 끝으로 본다 (#435).
 * API 는 ai-core 테스트 픽스처의 루프백 스텁이다 — 실제 Claude API 는 부르지 않는다. 키 · 응답 값은 가짜다.
 *
 * <p>test yml 단가 test-small = 입력 1 · 출력 5 USD/MTok, 월 한도 10 USD. 기본 모델을 test-small · 최대 출력 100 으로 덮어 예상치를 작게 둔다.
 * {@code budget-probe} 기능만 최대 출력 3,000,000 이라 예상치(15 USD)가 한도보다 크다. 재시도 0 · 동시 상한 1 · 응답 타임아웃 1초.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:operations-ai-metering;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
        + "INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "operations.jobs.worker.enabled=false",
    "ai.enabled=true",
    "ai.api-key=test-only-key",
    "ai.allow-insecure-loopback=true",
    "ai.default-model=test-small",
    "ai.default-max-tokens=100",
    "ai.connect-timeout=2s",
    "ai.read-timeout=1s",
    "ai.request-timeout=1s",
    "ai.max-retries=0",
    "ai.max-concurrency=1",
    "ai.acquire-timeout=200ms",
    "ai.features.budget-probe.max-tokens=3000000"
})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AiUsageMeteringFlowTest {

    private static final String PROMPT_PROBE = "PromptLeakProbe-4d1e";
    private static final String RESPONSE_PROBE = "ResponseLeakProbe-b82c";
    private static final StubMessagesServer SERVER = new StubMessagesServer();

    @Autowired
    private AiClient aiClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @DynamicPropertySource
    static void stubServer(DynamicPropertyRegistry registry) {
        registry.add("ai.base-url", SERVER::baseUrl);
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Test
    @DisplayName("계량 인터셉터는 이 어댑터 하나다 — ai-core 가드가 allow-unmetered 없이 켠 채 기동을 허용했다")
    void singleInterceptorIsRegistered() {
        assertThat(applicationContext.getBeansOfType(AiCallInterceptor.class).values())
            .singleElement().isInstanceOf(AiUsageMeteringInterceptor.class);
        assertThat(applicationContext.getEnvironment().getProperty("ai.allow-unmetered")).isNull();
    }

    @Test
    @DisplayName("성공 — 예약 뒤 실측 토큰으로 COMPLETED 정산되고, 원장 · 로그에는 기능 · callerRef · 수치만 있고 프롬프트 · 응답 원문이 없다")
    void completedCallIsReservedAndSettled(CapturedOutput output) {
        SERVER.reply(ok(RESPONSE_PROBE, "end_turn"));

        AiResult result = aiClient.complete(AiRequest.of("flow-ok", null, "질문 " + PROMPT_PROBE).withCallerRef("job:101"));

        assertThat(result.text()).isEqualTo(RESPONSE_PROBE);
        Map<String, Object> row = single("job:101");
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("feature")).isEqualTo("flow-ok");
        assertThat(row.get("requested_model")).isEqualTo("test-small");
        assertThat(((Number) row.get("input_tokens")).longValue()).isEqualTo(12L);
        assertThat(((Number) row.get("output_tokens")).longValue()).isEqualTo(5L);
        assertThat(((Number) row.get("cost_micros")).longValue()).isPositive();
        assertThat(row.get("message_id")).isEqualTo("msg_stub");
        assertThat(row.values()).allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain(PROMPT_PROBE).doesNotContain(RESPONSE_PROBE));
        assertThat(output).doesNotContain(PROMPT_PROBE).doesNotContain(RESPONSE_PROBE);
    }

    /**
     * 구조화 출력은 SDK 가 결과 타입에서 스키마를 만든다 — 그 생성기가 swagger 애노테이션 클래스를 읽는다. operations 클래스패스(springdoc 의 jakarta 판과 합쳐진
     * 애노테이션)에서도 스키마가 만들어지고 원장에 structured=true 로 남는지 본다 (ai-core build.gradle 의 swagger-annotations 판 교체).
     */
    @Test
    @DisplayName("구조화 출력 — operations 클래스패스에서 스키마를 만들어 부르고, 원장에 structured 로 정산된다")
    void structuredCallIsSettled() {
        SERVER.reply(ok("{\"size\":\"M\",\"chestCm\":102}", "end_turn"));

        SizeRow row = aiClient.complete(AiRequest.of("flow-structured", null, "치수").withCallerRef("job:107"), SizeRow.class).value();

        assertThat(row).isEqualTo(new SizeRow("M", 102));
        Map<String, Object> ledger = single("job:107");
        assertThat(ledger.get("status")).isEqualTo("COMPLETED");
        assertThat(ledger.get("structured")).isEqualTo(true);
    }

    record SizeRow(String size, int chestCm) {

    }

    @Test
    @DisplayName("한도 초과 — 예상치가 월 한도보다 크면 BUDGET_EXCEEDED 이고 HTTP 요청 · 원장 행이 없다")
    void budgetExceededSendsNothing(CapturedOutput output) {
        int receivedBefore = SERVER.received().size();

        assertThatThrownBy(() -> aiClient.complete(AiRequest.of("budget-probe", null, PROMPT_PROBE).withCallerRef("job:102")))
            .isInstanceOfSatisfying(AiException.class, exception -> assertThat(exception.reason()).isEqualTo(Reason.BUDGET_EXCEEDED));

        assertThat(SERVER.received()).hasSize(receivedBefore);
        assertThat(rows("job:102")).isEmpty();
        assertThat(output).contains("ai call estimate exceeds monthly budget").doesNotContain(PROMPT_PROBE);
    }

    @Test
    @DisplayName("오류 응답 — FAILED 로 정산하고 비용 0, 원인 · 상태 코드를 남긴다")
    void failedCallIsRecorded() {
        SERVER.reply(error(500, "api_error"));

        assertThatThrownBy(() -> aiClient.complete(AiRequest.of("flow-fail", null, PROMPT_PROBE).withCallerRef("job:103")))
            .isInstanceOf(AiException.class);

        Map<String, Object> row = single("job:103");
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("cost_micros")).longValue()).isZero();
        assertThat(((Number) row.get("http_status")).intValue()).isEqualTo(500);
        assertThat(row.get("reason")).isEqualTo("UNAVAILABLE");
    }

    @Test
    @DisplayName("타임아웃 — TIMEOUT 으로 정산하고 예약한 예상치를 비용으로 남긴다")
    void timeoutCallKeepsEstimate() {
        SERVER.reply(ok(RESPONSE_PROBE, "end_turn").delayed(1_500));

        assertThatThrownBy(() -> aiClient.complete(AiRequest.of("flow-timeout", null, PROMPT_PROBE).withCallerRef("job:104")))
            .isInstanceOfSatisfying(AiException.class, exception -> assertThat(exception.reason()).isEqualTo(Reason.UNAVAILABLE));

        Map<String, Object> row = single("job:104");
        assertThat(row.get("status")).isEqualTo("TIMEOUT");
        assertThat(((Number) row.get("cost_micros")).longValue()).isEqualTo(((Number) row.get("estimate_cost_micros")).longValue()).isPositive();
    }

    @Test
    @DisplayName("보내지 못함 — 동시 상한이 차서 BUSY 면 NOT_SENT 로 정산해 예약을 풀고(비용 0) 요청을 보내지 않는다")
    void notSentReleasesReservation() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        SERVER.reply(ok(RESPONSE_PROBE, "end_turn").heldUntil(release));
        int receivedBefore = SERVER.received().size();
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
            try {
                aiClient.complete(AiRequest.of("flow-hold", null, PROMPT_PROBE).withCallerRef("job:105"));
            } catch (AiException ignored) {
                // 응답 타임아웃(1초)으로 끝난다 — 자리를 쥐는 것이 목적이다
            }
        });
        try {
            awaitReceived(receivedBefore + 1);

            assertThatThrownBy(() -> aiClient.complete(AiRequest.of("flow-busy", null, PROMPT_PROBE).withCallerRef("job:106")))
                .isInstanceOfSatisfying(AiException.class, exception -> assertThat(exception.reason()).isEqualTo(Reason.BUSY));
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        assertThat(SERVER.received()).hasSize(receivedBefore + 1);
        Map<String, Object> row = single("job:106");
        assertThat(row.get("status")).isEqualTo("NOT_SENT");
        assertThat(row.get("reason")).isEqualTo("BUSY");
        assertThat(((Number) row.get("cost_micros")).longValue()).isZero();
    }

    @Test
    @DisplayName("대화 도구 루프 — 호출 2번(도구 호출 → 결과 첨부 재호출)이 원장 2행으로 각각 COMPLETED 정산되고, 도구 인자 원문이 원장 · 로그에 없다")
    void toolLoopRecordsOneRowPerCall(CapturedOutput output) {
        AiTool lookup = new AiTool("lookup_order", "주문 상태를 조회한다",
            "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"],\"additionalProperties\":false}");
        SERVER.reply(okToolUse("toolu_flow1", "lookup_order", "{\"orderId\":\"" + PROMPT_PROBE + "\"}")).reply(ok(RESPONSE_PROBE, "end_turn"));
        AiConversationRequest request = AiConversationRequest.of("flow-chat", null, List.of(AiMessage.user("주문 " + PROMPT_PROBE)), List.of(lookup))
            .withCallerRef("chat:201");

        AiConversationResult first = aiClient.converse(request);
        AiConversationResult second = aiClient.converse(request.append(first.assistantMessage(),
            AiMessage.toolResults(List.of(AiContent.toolResult(first.toolCalls().getFirst().id(), "{\"status\":\"SHIPPED\"}")))));

        assertThat(second.text()).isEqualTo(RESPONSE_PROBE);
        List<Map<String, Object>> rows = rows("chat:201");
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("COMPLETED");
            assertThat(row.get("feature")).isEqualTo("flow-chat");
            assertThat(((Number) row.get("cost_micros")).longValue()).isPositive();
            assertThat(row.values()).allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain(PROMPT_PROBE).doesNotContain(RESPONSE_PROBE));
        });
        assertThat(rows).extracting(row -> ((Number) row.get("input_tokens")).longValue()).containsExactlyInAnyOrder(30L, 12L);
        assertThat(output).doesNotContain(PROMPT_PROBE).doesNotContain(RESPONSE_PROBE);
    }

    @Test
    @DisplayName("스트림 취소 — UNUSABLE · 원인 null · message_start 까지 받은 부분 사용량으로 1행 정산된다")
    void cancelledStreamIsSettledWithPartialUsage() {
        SERVER.reply(stream(List.of(messageStart(21), blockStart(0, "{\"type\":\"text\",\"text\":\"\"}"), textDelta(0, "첫 조각"), textDelta(0, "다음")), 50, 0));

        AiConversationResult result = aiClient.converseStreaming(
            AiConversationRequest.of("flow-stream", null, List.of(AiMessage.user("질문"))).withCallerRef("chat:202"), delta -> false);

        assertThat(result.cancelled()).isTrue();
        Map<String, Object> row = single("chat:202");
        assertThat(row.get("status")).isEqualTo("UNUSABLE");
        assertThat(row.get("reason")).isNull();
        assertThat(((Number) row.get("input_tokens")).longValue()).isEqualTo(21L);
        assertThat(((Number) row.get("output_tokens")).longValue()).isEqualTo(1L);
        assertThat(row.get("message_id")).isEqualTo("msg_stream");
    }

    private void awaitReceived(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (SERVER.received().size() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("스텁이 요청을 받지 못했습니다");
            }
            Thread.sleep(10);
        }
    }

    private Map<String, Object> single(String callerRef) {
        List<Map<String, Object>> rows = rows(callerRef);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private List<Map<String, Object>> rows(String callerRef) {
        return jdbcTemplate.queryForList("select * from operations.ai_usage_call where caller_ref = ?", callerRef);
    }
}
