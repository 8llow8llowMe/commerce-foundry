package com.commercefoundry.ai.client;

import static com.commercefoundry.ai.support.StubMessagesServer.error;
import static com.commercefoundry.ai.support.StubMessagesServer.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.ai.client.AiException.Reason;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.ai.support.StubMessagesServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 실제 API 대신 루프백 스텁으로 SDK 의 요청 · 재시도 · 응답 해석을 그대로 거친다 (#215 완료 기준 — 마스킹 · 재시도가 공통으로 적용된다).
 * 픽스처 값은 가짜다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClaudeAiClientTest {

    private static final String SECRET_PROMPT = "PromptLeakProbe";
    private static final String SECRET_RESPONSE = "ResponseLeakProbe";

    private StubMessagesServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new StubMessagesServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    @DisplayName("성공하면 텍스트 · 모델 · 토큰 수 · 멈춘 이유 · 메시지 ID 를 돌려준다")
    void returnsResult() {
        server.reply(ok("hello", "end_turn"));

        AiResult result = client(properties(Map.of(), 2, 4, true)).complete(AiRequest.of("size-chart", "system", "prompt"));

        assertThat(result.text()).isEqualTo("hello");
        assertThat(result.model()).isEqualTo("claude-opus-5-5");
        assertThat(result.inputTokens()).isEqualTo(12);
        assertThat(result.outputTokens()).isEqualTo(5);
        assertThat(result.stopReason()).isEqualTo("end_turn");
        assertThat(result.messageId()).isEqualTo("msg_stub");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("보내는 본문에서 개인정보가 가려진다 — 호출자가 아는 값 · 이메일 · 전화번호 · 주소. 가리지 않고 보내는 경로가 없다")
    void masksBeforeSending() {
        server.reply(ok("ok", "end_turn"));
        AiRequest request = new AiRequest("cs-reply", "고객 Hong Gildong 의 문의다",
            "연락처 010-1234-5678, 메일 member@example.com, 주소 테헤란로 123 으로 보내 주세요. 주문 CF-20261024-0001",
            List.of("Hong Gildong", "CF-20261024-0001"));

        client(properties(Map.of(), 2, 4, false)).complete(request);

        String body = server.received().getFirst().body();
        assertThat(body).doesNotContain("Hong Gildong").doesNotContain("010-1234-5678").doesNotContain("member@example.com")
            .doesNotContain("테헤란로 123").doesNotContain("CF-20261024-0001")
            .contains("[PII]").contains("[PHONE]").contains("[EMAIL]").contains("[ADDRESS]");
    }

    @Test
    @DisplayName("기능 설정의 모델 · 최대 토큰 · effort 가 요청에 실린다. 설정에 없는 기능은 기본값이다")
    void appliesFeatureSettings() {
        server.reply(ok("ok", "end_turn"));
        AiProperties properties = properties(Map.of("size-chart", new AiProperties.Feature("claude-haiku-5-5", 256, "low")), 2, 4, false);
        AiClient client = client(properties);

        client.complete(AiRequest.of("size-chart", null, "prompt"));
        client.complete(AiRequest.of("sales-summary", null, "prompt"));

        assertThat(server.received().get(0).body()).contains("\"model\":\"claude-haiku-5-5\"").contains("\"max_tokens\":256").contains("\"effort\":\"low\"");
        assertThat(server.received().get(1).body()).contains("\"model\":\"claude-opus-5-5\"").contains("\"max_tokens\":16000").doesNotContain("effort");
    }

    @Test
    @DisplayName("거절 대체를 켜면 server-side fallback 베타 헤더와 fallbacks=default 가 실리고, 끄면 없다")
    void refusalFallbackToggle() {
        server.reply(ok("ok", "end_turn"));

        client(properties(Map.of(), 2, 4, true)).complete(AiRequest.of("size-chart", null, "prompt"));
        client(properties(Map.of(), 2, 4, false)).complete(AiRequest.of("size-chart", null, "prompt"));

        assertThat(server.received().get(0).header("anthropic-beta")).contains(ClaudeAiClient.FALLBACK_BETA);
        assertThat(server.received().get(0).body()).contains("\"fallbacks\":\"default\"");
        assertThat(server.received().get(1).header("anthropic-beta")).isNull();
        assertThat(server.received().get(1).body()).doesNotContain("fallbacks");
    }

    @Test
    @DisplayName("429 뒤 성공하면 SDK 가 다시 시도해 결과를 돌려준다")
    void retriesRateLimit() {
        server.reply(error(429, "rate_limit_error")).reply(ok("ok", "end_turn"));

        AiResult result = client(properties(Map.of(), 2, 4, false)).complete(AiRequest.of("size-chart", null, "prompt"));

        assertThat(result.text()).isEqualTo("ok");
        assertThat(server.received()).hasSize(2);
    }

    @Test
    @DisplayName("529(과부하)가 계속되면 재시도를 다 쓴 뒤 UNAVAILABLE(재시도 가능)이다 — 시도는 1 + max-retries 번")
    void overloadedExhaustsRetries() {
        server.reply(error(529, "overloaded_error"));

        assertFailure(() -> client(properties(Map.of(), 1, 4, false)).complete(AiRequest.of("size-chart", null, "prompt")), Reason.UNAVAILABLE, 529);
        assertThat(server.received()).hasSize(2);
        assertThat(Reason.UNAVAILABLE.retryable()).isTrue();
    }

    @Test
    @DisplayName("429 가 계속되면 RATE_LIMITED 다")
    void rateLimitedAfterRetries() {
        server.reply(error(429, "rate_limit_error"));

        assertFailure(() -> client(properties(Map.of(), 0, 4, false)).complete(AiRequest.of("size-chart", null, "prompt")), Reason.RATE_LIMITED, 429);
    }

    @Test
    @DisplayName("400 은 REJECTED, 401 은 UNAUTHORIZED 이고 다시 시도하지 않는다")
    void clientErrorsAreNotRetried() {
        server.reply(error(400, "invalid_request_error"));
        assertFailure(() -> client(properties(Map.of(), 2, 4, false)).complete(AiRequest.of("size-chart", null, "prompt")), Reason.REJECTED, 400);
        assertThat(server.received()).hasSize(1);

        server.reply(error(401, "authentication_error"));
        assertFailure(() -> client(properties(Map.of(), 2, 4, false)).complete(AiRequest.of("size-chart", null, "prompt")), Reason.UNAUTHORIZED, 401);
        assertThat(server.received()).hasSize(2);
        assertThat(Reason.REJECTED.retryable()).isFalse();
        assertThat(Reason.UNAUTHORIZED.retryable()).isFalse();
    }

    @Test
    @DisplayName("응답 타임아웃이면 UNAVAILABLE 이다")
    void readTimeoutIsUnavailable() {
        server.reply(ok("late", "end_turn").delayed(1_500));
        AiProperties properties = new AiProperties(true, "test-key", server.baseUrl(), null, null,
            Duration.ofSeconds(2), Duration.ofMillis(300), Duration.ofSeconds(2), 0, 4, Duration.ofSeconds(1), false, Map.of(), null, null, true, true);

        assertFailure(() -> AiClients.create(properties).complete(AiRequest.of("size-chart", null, "prompt")), Reason.UNAVAILABLE, null);
    }

    @Test
    @DisplayName("모델이 거절(stop_reason=refusal)하면 REFUSED 이고 다시 시도하지 않는다")
    void refusal() {
        server.reply(ok("", "refusal"));

        assertFailure(() -> client(properties(Map.of(), 2, 4, true)).complete(AiRequest.of("size-chart", null, "prompt")), Reason.REFUSED, null);
        assertThat(server.received()).hasSize(1);
    }

    @Test
    @DisplayName("최대 토큰에서 잘리면 결과는 돌려주되 truncated 로 알린다")
    void truncated() {
        server.reply(ok("partial", "max_tokens"));

        assertThat(client(properties(Map.of(), 2, 4, false)).complete(AiRequest.of("size-chart", null, "prompt")).truncated()).isTrue();
    }

    @Test
    @DisplayName("동시 상한이 차 있으면 대기 시간을 넘긴 호출은 BUSY 이고 API 에 닿지 않는다")
    void concurrencyLimit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.reply(ok("first", "end_turn").heldUntil(release));
        AiProperties properties = new AiProperties(true, "test-key", server.baseUrl(), null, null,
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5), 0, 1, Duration.ofMillis(200), false, Map.of(), null, null, true, true);
        AiClient client = AiClients.create(properties);

        CompletableFuture<AiResult> first = CompletableFuture.supplyAsync(() -> client.complete(AiRequest.of("size-chart", null, "prompt")));
        awaitReceived(1);

        assertFailure(() -> client.complete(AiRequest.of("size-chart", null, "prompt")), Reason.BUSY, null);
        assertThat(server.received()).hasSize(1);

        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).text()).isEqualTo("first");
    }

    @Test
    @DisplayName("로그에는 기능 · 토큰 · 상태만 남고 프롬프트 · 응답 원문 · API 오류 본문은 성공 · 실패 어디에도 없다")
    void logsWithoutContent(CapturedOutput output) {
        server.reply(ok(SECRET_RESPONSE, "end_turn")).reply(error(400, "invalid_request_error"));
        AiClient client = client(properties(Map.of(), 0, 4, false));

        client.complete(AiRequest.of("size-chart", SECRET_PROMPT, SECRET_PROMPT));
        assertThatThrownBy(() -> client.complete(AiRequest.of("size-chart", SECRET_PROMPT, SECRET_PROMPT))).isInstanceOf(AiException.class);

        assertThat(output.getAll()).contains("ai call completed. feature=size-chart").contains("ai call failed. feature=size-chart")
            .doesNotContain(SECRET_PROMPT).doesNotContain(SECRET_RESPONSE).doesNotContain("stub error body");
    }

    @Test
    @DisplayName("요청 · 결과 · 예외의 문자열 표현에 원문이 없다")
    void toStringHidesContent() {
        AiRequest request = new AiRequest("size-chart", SECRET_PROMPT, SECRET_PROMPT, List.of("Hong Gildong"));
        AiResult result = new AiResult(SECRET_RESPONSE, "m", 1, 1, "end_turn", "id");

        assertThat(request.toString()).doesNotContain(SECRET_PROMPT).doesNotContain("Hong Gildong").contains("images=0");
        assertThat(result.toString()).doesNotContain(SECRET_RESPONSE);
        assertThat(new AiException(Reason.REJECTED, "size-chart", 400, "invalid_request_error").getMessage()).doesNotContain(SECRET_PROMPT);
    }

    @Test
    @DisplayName("꺼져 있으면 언제나 DISABLED(재시도 불가)이고 API 에 닿지 않는다")
    void disabled() {
        AiClient client = AiClients.create(new AiProperties(false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

        assertFailure(() -> client.complete(AiRequest.of("size-chart", null, "prompt")), Reason.DISABLED, null);
        assertThat(server.received()).isEmpty();
    }

    @Test
    @DisplayName("이미지는 base64 블록으로 텍스트보다 앞에 실리고, 텍스트의 개인정보는 그대로 가려진다. 로그와 toString 에는 바이트가 없다")
    void sendsImageWithoutLeakingBytes(CapturedOutput output) {
        server.reply(ok("ok", "end_turn"));
        byte[] bytes = "ImageLeakProbe".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(bytes);
        AiRequest request = new AiRequest("size-chart", null, "연락처 010-1234-5678", List.of())
            .withImages(List.of(new AiImage(AiImage.JPEG, bytes)));

        client(properties(Map.of(), 0, 4, false)).complete(request);

        String body = server.received().getFirst().body();
        assertThat(body).contains("\"type\":\"image\"").contains("image/jpeg").contains(encoded).contains("[PHONE]").doesNotContain("010-1234-5678");
        assertThat(body.indexOf("image/jpeg")).isLessThan(body.indexOf("[PHONE]"));
        assertThat(request.toString()).doesNotContain(encoded).contains("images=1");
        assertThat(output.getAll()).contains("imageCount=1").doesNotContain(encoded).doesNotContain("ImageLeakProbe");
    }

    @Test
    @DisplayName("이미지가 장수 상한을 넘으면 INPUT_TOO_LONG 이고 호출하지 않는다")
    void rejectsTooManyImages() {
        List<AiImage> images = new ArrayList<>();
        for (int i = 0; i <= AiImage.MAX_COUNT; i++) {
            images.add(new AiImage(AiImage.PNG, new byte[] {1, 2, 3}));
        }

        assertFailure(() -> client(properties(Map.of(), 0, 4, false)).complete(AiRequest.of("size-chart", null, "prompt").withImages(images)),
            Reason.INPUT_TOO_LONG, null);
        assertThat(server.received()).isEmpty();
    }

    @Test
    @DisplayName("시스템 + 사용자 프롬프트가 글자 수 상한을 넘으면 INPUT_TOO_LONG(재시도 불가)이고 마스킹 · 호출을 하지 않는다")
    void rejectsTooLongInput() {
        AiProperties properties = new AiProperties(true, "test-key", server.baseUrl(), null, null,
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5), 0, 4, Duration.ofSeconds(1), false, Map.of(), null, 10, true, true);

        assertFailure(() -> AiClients.create(properties).complete(AiRequest.of("size-chart", "12345", "678901")), Reason.INPUT_TOO_LONG, null);
        assertThat(server.received()).isEmpty();
        assertThat(Reason.INPUT_TOO_LONG.retryable()).isFalse();
    }

    @Test
    @DisplayName("응답에 텍스트가 없으면 INVALID_RESPONSE 이고 재시도하지 않는다 — 같은 입력이면 다시 같을 가능성이 높다")
    void emptyTextIsInvalid() {
        server.reply(ok("", "end_turn"));

        assertFailure(() -> client(properties(Map.of(), 2, 4, false)).complete(AiRequest.of("size-chart", null, "prompt")), Reason.INVALID_RESPONSE, null);
        assertThat(Reason.INVALID_RESPONSE.retryable()).isFalse();
    }

    private AiProperties properties(Map<String, AiProperties.Feature> features, int maxRetries, int maxConcurrency, boolean refusalFallback) {
        return new AiProperties(true, "test-key", server.baseUrl(), null, null,
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5), maxRetries, maxConcurrency, Duration.ofSeconds(1), refusalFallback,
            features, null, null, true, true);
    }

    private static AiClient client(AiProperties properties) {
        return AiClients.create(properties);
    }

    private void awaitReceived(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.received().size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static void assertFailure(Runnable call, Reason reason, Integer statusCode) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(AiException.class, exception -> {
                assertThat(exception.reason()).isEqualTo(reason);
                assertThat(exception.statusCode()).isEqualTo(statusCode);
                assertThat(exception.getCause()).as("SDK 예외를 잇지 않는다").isNull();
            });
    }
}
