package com.commercefoundry.ai.client;

import static com.commercefoundry.ai.support.StubMessagesServer.error;
import static com.commercefoundry.ai.support.StubMessagesServer.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.ai.client.AiException.Reason;
import com.commercefoundry.ai.metering.AiCallContext;
import com.commercefoundry.ai.metering.AiCallHandle;
import com.commercefoundry.ai.metering.AiCallInterceptor;
import com.commercefoundry.ai.metering.AiCallOutcome;
import com.commercefoundry.ai.metering.AiCallOutcome.Status;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.ai.support.StubMessagesServer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 계량 인터셉터가 호출 앞뒤에 끼는 순서 · 결과 분류 · 사용량을 루프백 스텁으로 본다 (#215 PR-B1). 픽스처 값은 가짜다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClaudeAiMeteringTest {

    private static final String VALID = "{\"size\":\"M\",\"chestCm\":102}";

    record Row(String size, int chestCm) {

    }

    private StubMessagesServer server;

    @BeforeEach
    void startServer() {
        server = new StubMessagesServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    @DisplayName("캐시 토큰을 읽어 결과와 COMPLETED 결과에 실측으로 싣는다 — 기존 inputTokens · outputTokens 도 그대로다")
    void completedWithCacheTokens() {
        server.reply(ok("hello", "end_turn", 30, 7, 200, 1000));
        Recording interceptor = new Recording();

        AiResult result = client(interceptor, 2, 4, Duration.ofSeconds(5)).complete(AiRequest.of("size-chart", null, "prompt"));

        AiUsage expected = new AiUsage(30, 7, 200, 1000);
        assertThat(result.usage()).isEqualTo(expected);
        assertThat(result.inputTokens()).isEqualTo(30);
        assertThat(result.outputTokens()).isEqualTo(7);
        AiCallOutcome outcome = interceptor.single();
        assertThat(outcome.status()).isEqualTo(Status.COMPLETED);
        assertThat(outcome.usage()).isEqualTo(expected);
        assertThat(outcome.model()).isEqualTo("claude-opus-5-5");
        assertThat(outcome.messageId()).isEqualTo("msg_stub");
        assertThat(outcome.reason()).isNull();
        assertThat(outcome.statusCode()).isNull();
        assertThat(interceptor.handles).containsExactly(interceptor.issued.getFirst());
    }

    @Test
    @DisplayName("API 가 캐시 토큰을 싣지 않으면 0 이다")
    void missingCacheTokensAreZero() {
        server.reply(ok("hello", "end_turn"));
        Recording interceptor = new Recording();

        AiResult result = client(interceptor, 0, 4, Duration.ofSeconds(5)).complete(AiRequest.of("size-chart", null, "prompt"));

        assertThat(result.usage()).isEqualTo(new AiUsage(12, 5, 0, 0));
    }

    @Test
    @DisplayName("컨텍스트에는 호출자 식별자 · 요청 모델 · 최대 토큰 · 마스킹 뒤 글자 수 · 이미지 장수 · 구조화 여부가 실리고 원문은 없다")
    void contextCarriesMetadataOnly() {
        server.reply(ok(VALID, "end_turn"));
        Recording interceptor = new Recording();
        AiRequest request = new AiRequest("size-chart", "sys", "연락처 010-1234-5678", List.of())
            .withImages(List.of(new AiImage(AiImage.PNG, new byte[] {1, 2, 3})))
            .withCallerRef("job:42");

        client(interceptor, 0, 4, Duration.ofSeconds(5), Map.of("size-chart", new AiProperties.Feature("claude-haiku-5-5", 300, null)))
            .complete(request, Row.class);

        AiCallContext context = interceptor.contexts.getFirst();
        assertThat(context.callId()).isNotBlank();
        assertThat(context.feature()).isEqualTo("size-chart");
        assertThat(context.callerRef()).isEqualTo("job:42");
        assertThat(context.requestedModel()).isEqualTo("claude-haiku-5-5");
        assertThat(context.maxTokens()).isEqualTo(300);
        assertThat(context.inputChars()).isEqualTo("sys".length() + "연락처 [PHONE]".length());
        assertThat(context.imageCount()).isEqualTo(1);
        assertThat(context.structured()).isTrue();
        assertThat(context.toString()).doesNotContain("010-1234-5678");
    }

    @Test
    @DisplayName("before 가 BUDGET_EXCEEDED 를 던지면 그대로 나가고 HTTP 요청 · after 가 없다")
    void beforeRejects() {
        server.reply(ok("never", "end_turn"));
        Recording interceptor = new Recording(context -> {
            throw new AiException(Reason.BUDGET_EXCEEDED, context.feature());
        });

        assertThatThrownBy(() -> client(interceptor, 2, 4, Duration.ofSeconds(5)).complete(AiRequest.of("size-chart", null, "prompt")))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BUDGET_EXCEEDED));
        assertThat(server.received()).isEmpty();
        assertThat(interceptor.outcomes).isEmpty();
        assertThat(Reason.BUDGET_EXCEEDED.retryable()).isFalse();
    }

    @Test
    @DisplayName("before 가 AiException 이 아닌 예외를 던지면 METERING_FAILED(재시도 가능)이고 보내지 않는다 — 예외 메시지를 남기지 않는다")
    void beforeCrashes(CapturedOutput output) {
        Recording interceptor = new Recording(context -> {
            throw new IllegalStateException("LedgerLeakProbe");
        });

        assertThatThrownBy(() -> client(interceptor, 2, 4, Duration.ofSeconds(5)).complete(AiRequest.of("size-chart", null, "prompt")))
            .isInstanceOfSatisfying(AiException.class, e -> {
                assertThat(e.reason()).isEqualTo(Reason.METERING_FAILED);
                assertThat(e.getCause()).isNull();
            });
        assertThat(server.received()).isEmpty();
        assertThat(Reason.METERING_FAILED.retryable()).isTrue();
        assertThat(output.getAll()).contains("stage=before").doesNotContain("LedgerLeakProbe");
    }

    @Test
    @DisplayName("거절(refusal)은 UNUSABLE 이고 실측 사용량이 실린다")
    void refusalIsUnusable() {
        server.reply(ok("", "refusal", 40, 3, 0, 0));
        Recording interceptor = new Recording();

        assertThatThrownBy(() -> client(interceptor, 2, 4, Duration.ofSeconds(5)).complete(AiRequest.of("size-chart", null, "prompt")))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.REFUSED));
        assertUnusable(interceptor.single(), Reason.REFUSED, new AiUsage(40, 3, 0, 0));
    }

    @Test
    @DisplayName("구조화 출력이 잘리면 UNUSABLE 이고 실측 사용량이 실린다")
    void truncatedStructuredIsUnusable() {
        server.reply(ok("{\"size\":", "max_tokens", 50, 300, 0, 0));
        Recording interceptor = new Recording();

        assertThatThrownBy(() -> client(interceptor, 0, 4, Duration.ofSeconds(5)).complete(AiRequest.of("size-chart", null, "prompt"), Row.class))
            .isInstanceOf(AiException.class);
        assertUnusable(interceptor.single(), Reason.INVALID_RESPONSE, new AiUsage(50, 300, 0, 0));
    }

    @Test
    @DisplayName("검증에 실패한 구조화 출력 · 빈 텍스트도 UNUSABLE 이다")
    void invalidOutputIsUnusable() {
        server.reply(ok(VALID, "end_turn", 20, 9, 0, 0)).reply(ok("", "end_turn", 20, 0, 0, 0));
        Recording interceptor = new Recording();
        AiClient client = client(interceptor, 0, 4, Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.complete(AiRequest.of("size-chart", null, "prompt"), Row.class, row -> List.of("bad")))
            .isInstanceOf(AiException.class);
        assertThatThrownBy(() -> client.complete(AiRequest.of("size-chart", null, "prompt"))).isInstanceOf(AiException.class);

        assertUnusable(interceptor.outcomes.get(0), Reason.INVALID_RESPONSE, new AiUsage(20, 9, 0, 0));
        assertUnusable(interceptor.outcomes.get(1), Reason.INVALID_RESPONSE, new AiUsage(20, 0, 0, 0));
    }

    @Test
    @DisplayName("오류 응답(400 · 429 · 529)은 FAILED 이고 상태 코드가 실리며 사용량은 없다")
    void errorResponseIsFailed() {
        server.reply(error(400, "invalid_request_error")).reply(error(429, "rate_limit_error")).reply(error(529, "overloaded_error"));
        Recording interceptor = new Recording();
        AiClient client = client(interceptor, 0, 4, Duration.ofSeconds(5));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.complete(AiRequest.of("size-chart", null, "prompt"))).isInstanceOf(AiException.class);
        }

        assertThat(interceptor.outcomes).extracting(AiCallOutcome::status).containsOnly(Status.FAILED);
        assertThat(interceptor.outcomes).extracting(AiCallOutcome::statusCode).containsExactly(400, 429, 529);
        assertThat(interceptor.outcomes).extracting(AiCallOutcome::reason).containsExactly(Reason.REJECTED, Reason.RATE_LIMITED, Reason.UNAVAILABLE);
        assertThat(interceptor.outcomes).extracting(AiCallOutcome::usage).containsOnlyNulls();
        assertThat(interceptor.outcomes).extracting(AiCallOutcome::messageId).containsOnlyNulls();
    }

    @Test
    @DisplayName("응답이 오지 않으면(응답 타임아웃) TIMEOUT 이고 사용량 · 상태 코드가 없다 — 원장은 예상치를 남긴다")
    void noResponseIsTimeout() {
        server.reply(ok("late", "end_turn").delayed(1_500));
        Recording interceptor = new Recording();

        assertThatThrownBy(() -> client(interceptor, 0, 4, Duration.ofMillis(300)).complete(AiRequest.of("size-chart", null, "prompt")))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE));

        AiCallOutcome outcome = interceptor.single();
        assertThat(outcome.status()).isEqualTo(Status.TIMEOUT);
        assertThat(outcome.usage()).isNull();
        assertThat(outcome.statusCode()).isNull();
        assertThat(outcome.model()).isEqualTo("claude-opus-5-5");
    }

    @Test
    @DisplayName("200 이지만 JSON 이 아니거나 사용량이 없으면 TIMEOUT(INVALID_RESPONSE)이고 사용량이 없다 — 원장은 예상치를 남긴다")
    void unreadableResponseWithoutUsageIsTimeout() {
        server.reply(raw("not json")).reply(raw("""
            {"id":"msg_stub","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn","stop_sequence":null}"""));
        Recording interceptor = new Recording();
        AiClient client = client(interceptor, 0, 4, Duration.ofSeconds(5));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.complete(AiRequest.of("size-chart", null, "prompt")))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE));
        }

        assertThat(interceptor.outcomes).extracting(AiCallOutcome::status).containsExactly(Status.TIMEOUT, Status.TIMEOUT);
        assertThat(interceptor.outcomes).extracting(AiCallOutcome::usage).containsOnlyNulls();
        assertThat(interceptor.outcomes).extracting(AiCallOutcome::reason).containsOnly(Reason.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("사용량은 읽었는데 다른 필수 필드(id)가 없어 해석하지 못하면 UNUSABLE 이고 실측 사용량이 실린다 — 과금된 응답이다")
    void unreadableResponseWithUsageIsUnusable() {
        server.reply(raw("""
            {"type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn","stop_sequence":null,
             "usage":{"input_tokens":21,"output_tokens":4}}"""));
        Recording interceptor = new Recording();

        assertThatThrownBy(() -> client(interceptor, 0, 4, Duration.ofSeconds(5)).complete(AiRequest.of("size-chart", null, "prompt")))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE));

        AiCallOutcome outcome = interceptor.single();
        assertThat(outcome.status()).isEqualTo(Status.UNUSABLE);
        assertThat(outcome.usage()).isEqualTo(new AiUsage(21, 4, 0, 0));
        assertThat(outcome.reason()).isEqualTo(Reason.INVALID_RESPONSE);
        assertThat(outcome.messageId()).isNull();
    }

    @Test
    @DisplayName("마스킹에 실패하면 MASKING_FAILED 이고 before · after 둘 다 부르지 않는다")
    void maskingFailureSkipsInterceptor() {
        Recording interceptor = new Recording();
        // 자바 정규식의 그룹 반복은 일치 길이만큼 재귀한다 — 긴 입력에서 StackOverflowError 로 마스킹이 실패한다
        AiProperties properties = new AiProperties(true, "test-key", server.baseUrl(), null, null, Duration.ofSeconds(2), Duration.ofSeconds(5),
            Duration.ofSeconds(5), 0, 4, Duration.ofSeconds(1), false, Map.of(), new AiProperties.Masking(Map.of("probe", "(a|b)+")), null, true, false);

        assertThatThrownBy(() -> AiClients.create(properties, List.of(interceptor)).complete(AiRequest.of("size-chart", null, "ab".repeat(90_000))))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.MASKING_FAILED));
        assertThat(interceptor.contexts).isEmpty();
        assertThat(interceptor.outcomes).isEmpty();
        assertThat(server.received()).isEmpty();
    }

    @Test
    @DisplayName("before 가 거절한 호출은 자리를 잡지 않는다 — 동시 상한 1 에서 바로 다음 호출이 대기 없이 성공한다")
    void beforeRejectionHoldsNoPermit() {
        server.reply(ok("hello", "end_turn"));
        AtomicBoolean rejectOnce = new AtomicBoolean(true);
        Recording interceptor = new Recording(context -> {
            if (rejectOnce.getAndSet(false)) {
                throw new AiException(Reason.BUDGET_EXCEEDED, context.feature());
            }
            return new Handle(context.callId());
        });
        AiClient client = AiClients.create(properties(0, 1, Duration.ofSeconds(5), Map.of(), false, Duration.ofMillis(50)), List.of(interceptor));

        assertThatThrownBy(() -> client.complete(AiRequest.of("size-chart", null, "prompt")))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BUDGET_EXCEEDED));
        assertThat(client.complete(AiRequest.of("size-chart", null, "prompt")).text()).isEqualTo("hello");

        assertThat(interceptor.outcomes).extracting(AiCallOutcome::status).containsExactly(Status.COMPLETED);
    }

    @Test
    @DisplayName("자리 대기 중 인터럽트되면 BUSY 이고 NOT_SENT 로 알린다 — 인터럽트 플래그는 지우지 않는다")
    void interruptedWhileAcquiringIsNotSent() {
        Recording interceptor = new Recording();
        AiClient client = client(interceptor, 0, 1, Duration.ofSeconds(5));

        boolean stillInterrupted;
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.complete(AiRequest.of("size-chart", null, "prompt")))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BUSY));
        } finally {
            // 플래그를 확인하면서 지운다 — 다음 테스트로 새지 않게
            stillInterrupted = Thread.interrupted();
        }

        assertThat(stillInterrupted).isTrue();
        assertThat(server.received()).isEmpty();
        AiCallOutcome outcome = interceptor.single();
        assertThat(outcome.status()).isEqualTo(Status.NOT_SENT);
        assertThat(outcome.reason()).isEqualTo(Reason.BUSY);
    }

    @Test
    @DisplayName("after 가 던져도 결과 · 원래 예외는 그대로이고 WARN 에는 예외 타입만 남는다")
    void afterFailureDoesNotBreakResult(CapturedOutput output) {
        server.reply(ok("hello", "end_turn")).reply(error(400, "invalid_request_error"));
        AiCallInterceptor broken = new AiCallInterceptor() {
            @Override
            public AiCallHandle before(AiCallContext context) {
                return new Handle(context.callId());
            }

            @Override
            public void after(AiCallHandle handle, AiCallOutcome outcome) {
                throw new IllegalStateException("LedgerLeakProbe");
            }
        };
        AiClient client = AiClients.create(properties(0, 4, Duration.ofSeconds(5), Map.of(), false), List.of(broken));

        assertThat(client.complete(AiRequest.of("size-chart", null, "prompt")).text()).isEqualTo("hello");
        assertThatThrownBy(() -> client.complete(AiRequest.of("size-chart", null, "prompt")))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.REJECTED));
        assertThat(output.getAll()).contains("stage=after").contains("IllegalStateException").doesNotContain("LedgerLeakProbe");
    }

    @Test
    @DisplayName("after 는 동시 상한 자리를 반납한 뒤에 불린다 — 상한 1 에서 after 안의 다른 호출이 자리를 얻는다")
    void afterRunsAfterRelease() {
        server.reply(ok("outer", "end_turn")).reply(ok("inner", "end_turn"));
        List<AiResult> inner = new CopyOnWriteArrayList<>();
        AtomicBoolean nested = new AtomicBoolean();
        AiClient[] holder = new AiClient[1];
        Recording interceptor = new Recording(context -> new Handle(context.callId()), outcome -> {
            // 바깥 호출의 after 에서 한 번만 — 자리가 아직 잡혀 있으면 acquire-timeout(200ms) 뒤 BUSY 다
            if (nested.compareAndSet(false, true)) {
                inner.add(holder[0].complete(AiRequest.of("size-chart", null, "inner")));
            }
        });
        holder[0] = AiClients.create(properties(0, 1, Duration.ofSeconds(5), Map.of(), false, Duration.ofMillis(200)), List.of(interceptor));

        assertThat(holder[0].complete(AiRequest.of("size-chart", null, "outer")).text()).isEqualTo("outer");

        assertThat(inner).extracting(AiResult::text).containsExactly("inner");
        assertThat(interceptor.outcomes).extracting(AiCallOutcome::status).containsExactly(Status.COMPLETED, Status.COMPLETED);
    }

    @Test
    @DisplayName("동시 상한 자리를 못 얻으면(BUSY) NOT_SENT 로 알려 예약을 풀게 한다 — HTTP 요청은 없다")
    void busyIsNotSent() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.reply(ok("first", "end_turn").heldUntil(release));
        Recording interceptor = new Recording();
        AiClient client = AiClients.create(properties(0, 1, Duration.ofSeconds(5), Map.of(), false, Duration.ofMillis(200)), List.of(interceptor));

        CompletableFuture<AiResult> first = CompletableFuture.supplyAsync(() -> client.complete(AiRequest.of("size-chart", null, "prompt")));
        awaitReceived(1);
        assertThatThrownBy(() -> client.complete(AiRequest.of("size-chart", null, "prompt")))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BUSY));
        release.countDown();
        first.get(5, TimeUnit.SECONDS);

        assertThat(server.received()).hasSize(1);
        assertThat(interceptor.outcomes).extracting(AiCallOutcome::status).containsExactlyInAnyOrder(Status.NOT_SENT, Status.COMPLETED);
        AiCallOutcome notSent = interceptor.outcomes.stream().filter(outcome -> outcome.status() == Status.NOT_SENT).findFirst().orElseThrow();
        assertThat(notSent.reason()).isEqualTo(Reason.BUSY);
        assertThat(notSent.usage()).isNull();
        assertThat(notSent.elapsedMs()).isZero();
    }

    @Test
    @DisplayName("길이 상한에 막힌 호출은 before · after 둘 다 부르지 않는다")
    void preflightRejectionSkipsInterceptor() {
        Recording interceptor = new Recording();
        AiProperties properties = new AiProperties(true, "test-key", server.baseUrl(), null, null,
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5), 0, 4, Duration.ofSeconds(1), false, Map.of(), null, 5, true, false);

        assertThatThrownBy(() -> AiClients.create(properties, List.of(interceptor)).complete(AiRequest.of("size-chart", null, "too long prompt")))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INPUT_TOO_LONG));
        assertThat(interceptor.contexts).isEmpty();
        assertThat(interceptor.outcomes).isEmpty();
    }

    @Test
    @DisplayName("직접 만들 때도 켜져 있으면 인터셉터가 하나여야 한다 — 없으면 allow-unmetered 일 때만, 둘이면 거절")
    void createChecksInterceptorCount() {
        AiProperties metered = properties(0, 4, Duration.ofSeconds(5), Map.of(), false);

        assertThatThrownBy(() -> AiClients.create(metered)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ai.allow-unmetered");
        assertThatThrownBy(() -> AiClients.create(metered, List.of(new Recording(), new Recording())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2개");
        assertThat(AiClients.create(properties(0, 4, Duration.ofSeconds(5), Map.of(), true))).isNotNull();
    }

    @Test
    @DisplayName("callerRef 는 '소문자 접두어:토막' 꼴(토막 1~3개, 100자 이하)만 받고, 거절 메시지에 값을 싣지 않는다")
    void callerRefRule() {
        AiRequest base = AiRequest.of("size-chart", null, "prompt");

        assertThat(base.callerRef()).isNull();
        assertThat(base.withCallerRef("job:ai-usage_42").callerRef()).isEqualTo("job:ai-usage_42");
        assertThat(base.withCallerRef("job:123:2").callerRef()).isEqualTo("job:123:2");
        assertThat(base.withCallerRef("ai-job:123:2:x").callerRef()).isEqualTo("ai-job:123:2:x");
        assertThat(base.withCallerRef("job:" + "1".repeat(64)).callerRef()).hasSize(68);
        assertThat(base.withCallerRef("job:1").withImages(List.of()).callerRef()).isEqualTo("job:1");
        List<String> invalids = List.of("", "job", "123", "JOB:1", "job:", "job:1:2:3:4", "job:" + "1".repeat(65),
            "job:" + "1".repeat(60) + ":" + "2".repeat(40), "a".repeat(32) + ":1", "홍길동", "job:홍길동", "job 1", "job:1 2", "member@example.com",
            "mail:member@example.com", "order:CF-20261024-0001.");
        for (String invalid : invalids) {
            assertThatThrownBy(() -> base.withCallerRef(invalid)).isInstanceOf(IllegalArgumentException.class)
                .message().doesNotContain("홍길동").doesNotContain("member@example.com");
        }
    }

    private static StubMessagesServer.Reply raw(String body) {
        return new StubMessagesServer.Reply(200, body, Map.of(), 0, null);
    }

    private static void assertUnusable(AiCallOutcome outcome, Reason reason, AiUsage usage) {
        assertThat(outcome.status()).isEqualTo(Status.UNUSABLE);
        assertThat(outcome.reason()).isEqualTo(reason);
        assertThat(outcome.usage()).isEqualTo(usage);
        assertThat(outcome.statusCode()).isNull();
        assertThat(outcome.messageId()).isEqualTo("msg_stub");
    }

    private AiClient client(AiCallInterceptor interceptor, int maxRetries, int maxConcurrency, Duration readTimeout) {
        return client(interceptor, maxRetries, maxConcurrency, readTimeout, Map.of());
    }

    private AiClient client(AiCallInterceptor interceptor, int maxRetries, int maxConcurrency, Duration readTimeout, Map<String, AiProperties.Feature> features) {
        return AiClients.create(properties(maxRetries, maxConcurrency, readTimeout, features, false), List.of(interceptor));
    }

    private AiProperties properties(int maxRetries, int maxConcurrency, Duration readTimeout, Map<String, AiProperties.Feature> features, boolean allowUnmetered) {
        return properties(maxRetries, maxConcurrency, readTimeout, features, allowUnmetered, Duration.ofSeconds(1));
    }

    private AiProperties properties(int maxRetries, int maxConcurrency, Duration readTimeout, Map<String, AiProperties.Feature> features, boolean allowUnmetered,
                                    Duration acquireTimeout) {
        return new AiProperties(true, "test-key", server.baseUrl(), null, null, Duration.ofSeconds(2), readTimeout, Duration.ofSeconds(5), maxRetries,
            maxConcurrency, acquireTimeout, false, features, null, null, true, allowUnmetered);
    }

    private void awaitReceived(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.received().size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private record Handle(String callId) implements AiCallHandle {

    }

    /** 받은 컨텍스트 · 핸들 · 결과를 기록한다. */
    private static final class Recording implements AiCallInterceptor {

        private final Function<AiCallContext, AiCallHandle> onBefore;
        private final Consumer<AiCallOutcome> onAfter;
        private final List<AiCallContext> contexts = new CopyOnWriteArrayList<>();
        private final List<AiCallHandle> issued = new CopyOnWriteArrayList<>();
        private final List<AiCallHandle> handles = new CopyOnWriteArrayList<>();
        private final List<AiCallOutcome> outcomes = new CopyOnWriteArrayList<>();

        Recording() {
            this(context -> new Handle(context.callId()), outcome -> {
            });
        }

        Recording(Function<AiCallContext, AiCallHandle> onBefore) {
            this(onBefore, outcome -> {
            });
        }

        Recording(Function<AiCallContext, AiCallHandle> onBefore, Consumer<AiCallOutcome> onAfter) {
            this.onBefore = onBefore;
            this.onAfter = onAfter;
        }

        @Override
        public AiCallHandle before(AiCallContext context) {
            contexts.add(context);
            AiCallHandle handle = onBefore.apply(context);
            issued.add(handle);
            return handle;
        }

        @Override
        public void after(AiCallHandle handle, AiCallOutcome outcome) {
            handles.add(handle);
            outcomes.add(outcome);
            onAfter.accept(outcome);
        }

        AiCallOutcome single() {
            assertThat(outcomes).hasSize(1);
            return outcomes.getFirst();
        }
    }
}
