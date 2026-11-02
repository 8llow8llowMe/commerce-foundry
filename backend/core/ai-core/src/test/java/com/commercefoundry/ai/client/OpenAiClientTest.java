package com.commercefoundry.ai.client;

import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.ai.metering.AiCallOutcome.Status;
import com.commercefoundry.ai.properties.AiProperties;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

class OpenAiClientTest {
    HttpServer server;
    List<String> requests = new CopyOnWriteArrayList<>();
    Queue<String> replies = new java.util.concurrent.ConcurrentLinkedQueue<>();
    String sse;
    int status = 200;
    java.util.concurrent.CountDownLatch entered, release;
    RecordingInterceptor meter = new RecordingInterceptor();
    List<AutoCloseable> clients = new ArrayList<>();

    @BeforeEach

    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
                    if (entered != null) {
                        entered.countDown();
                        try {
                            release.await(3, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    assertThat(exchange.getRequestURI().getPath()).isEqualTo("/responses");
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer stub-key");
                    requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    String reply = sse != null ? sse : replies.remove();
                    byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", sse == null ? "application/json" : "text/event-stream");
                    exchange.getResponseHeaders().add("retry-after-ms", "1");
                    exchange.sendResponseHeaders(status, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
    }

    @AfterEach

    void stop() throws Exception {
        for (AutoCloseable client : clients) client.close();
        server.stop(0);
    }

    AiProperties properties(Map<String, AiProperties.Feature> features) {
        return new AiProperties(
                true,
                null,
                null,
                null,
                100,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                0,
                1,
                Duration.ofMillis(20),
                false,
                features,
                null,
                200000,
                true,
                false,
                "openai",
                new AiProperties.OpenAi(
                        "stub-key",
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "stub-model"));
    }

    AiClient client() {
        AiClient client = AiClients.create(properties(Map.of()), List.of(meter));
        clients.add((AutoCloseable) client);
        return client;
    }

    static String response(String output, String state) {
        return "{\"id\":\"resp_stub\",\"object\":\"response\",\"model\":\"stub-model\",\"status\":\""
                + state
                + "\","
                + "\"output\":"
                + output
                + ",\"usage\":{\"input_tokens\":12,\"input_tokens_details\":{\"cached_tokens\":4},\"output_tokens\":5}}";
    }

    static String text(String text) {
        return "[{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":"
                + AiJson.write(AiJson.MAPPER.getNodeFactory().textNode(text))
                + ",\"annotations\":[]}]}]";
    }

    static String event(String type, String body) {
        return "event: " + type + "\ndata: " + body + "\n\n";
    }

    @Test

    void openAiAndClaudeShareTheProcessConcurrencyLimit() throws Exception {
        entered = new java.util.concurrent.CountDownLatch(1);
        release = new java.util.concurrent.CountDownLatch(1);
        replies.add(response(text("hello"), "completed"));
        AiProperties p = properties(Map.of("legacy", new AiProperties.Feature(null, null, null, null, "anthropic")));
        p =
                new AiProperties(
                        p.enabled(),
                        "claude-stub-key",
                        p.openai().baseUrl(),
                        p.defaultModel(),
                        p.defaultMaxTokens(),
                        p.connectTimeout(),
                        p.readTimeout(),
                        p.requestTimeout(),
                        p.maxRetries(),
                        p.maxConcurrency(),
                        p.acquireTimeout(),
                        p.refusalFallback(), p.features(), p.masking(), p.maxInputChars(), p.allowInsecureLoopback(), p.allowUnmetered(), p.provider(), p.openai());
        AiClient client = AiClients.create(p, List.of(meter));
        clients.add((AutoCloseable) client);
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> client.complete(AiRequest.of("chat", null, "hello")));
        try {
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> client.complete(AiRequest.of("legacy", null, "hello")))
                   .isInstanceOfSatisfying(AiException.class, error -> assertThat(error.reason()).isEqualTo(AiException.Reason.BUSY));
        } finally {
            release.countDown();
        }
        assertThat(first.get(3, java.util.concurrent.TimeUnit.SECONDS).text()).isEqualTo("hello");
        assertThat(requests).hasSize(1);
        assertThat(meter.outcomes).extracting(outcome -> outcome.status()).containsExactly(Status.NOT_SENT, Status.COMPLETED);
    }

    @Test

    void textMasksCachesAndDisablesStorage() {
        replies.add(response(text("hello"), "completed"));
        AiResult result = client().complete(AiRequest.of("chat", "system", "mail member@example.com"));
        assertThat(result.text()).isEqualTo("hello");
        assertThat(result.usage()).isEqualTo(new AiUsage(8, 5, 0, 4));
        assertThat(requests.getFirst()).contains("\"store\":false", "reasoning.encrypted_content", "[EMAIL]").doesNotContain("member@example.com");
        assertThat(meter.contexts.getFirst().provider()).isEqualTo("openai");
        assertThat(meter.single().status()).isEqualTo(Status.COMPLETED);
    }

    @Test

    void eofWithoutTerminalFails() {
        sse =
                event(
                        "response.output_text.delta",
                        "{\"type\":\"response.output_text.delta\",\"delta\":\"partial\",\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"sequence_number\":1}");
        assertThatThrownBy(() -> client().converseStreaming(conversation(), delta -> true))
               .isInstanceOfSatisfying(AiException.class, error -> assertThat(error.reason()).isEqualTo(AiException.Reason.UNAVAILABLE));
        assertThat(meter.single().status()).isEqualTo(Status.TIMEOUT);
    }

    AiConversationRequest conversation() {
        return AiConversationRequest.of("chat", null, List.of(AiMessage.user("hello")));
    }

    String deltaStream() {
        return event(
                "response.output_text.delta",
                "{\"type\":\"response.output_text.delta\",\"delta\":\"partial\",\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"sequence_number\":1}");
    }

    @Test

    void partialCancellationReturnsNoToolsOrAssistant() {
        sse = deltaStream();
        var result = client().converseStreaming(conversation(), delta -> false);
        assertThat(result.text()).isEqualTo("partial");
        assertThat(result.cancelled()).isTrue();
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.assistantMessage()).isNull();
        assertThat(meter.single().status()).isEqualTo(Status.TIMEOUT);
    }

    @Test

    void listenerExceptionsArePreservedAndMeteredOnce() {
        RuntimeException original = new IllegalArgumentException("listener-original");
        sse = deltaStream();
        assertThatThrownBy(() -> client().converseStreaming(conversation(), delta -> {
                                                    throw original;
                                                }))
               .isSameAs(original);
        assertThat(meter.single().status()).isEqualTo(Status.TIMEOUT);
        assertThat(meter.single().reason()).isNull();
    }

    @Test

    void startExceptionIsNotSent() {
        RuntimeException original = new IllegalStateException("listener-original");
        assertThatThrownBy(() -> client().converseStreaming(conversation(), new AiStreamListener() {
                                                    public void onStart(AiStreamHandle handle) {
                                                        throw original;
                                                    }

                                                    public boolean onTextDelta(String delta) {
                                                        return true;
                                                    }
                                                }))
               .isSameAs(original);
        assertThat(requests).isEmpty();
        assertThat(meter.single().status()).isEqualTo(Status.NOT_SENT);
    }

    @Test

    void beforeRejectionNeverSendsOrCallsAfter() {
        meter = new RecordingInterceptor(context -> {
                            throw new AiException(AiException.Reason.METERING_FAILED, "chat");
                        });
        assertThatThrownBy(() -> client().complete(AiRequest.of("chat", null, "hello"))).isInstanceOf(AiException.class);
        assertThat(requests).isEmpty();
        assertThat(meter.outcomes).isEmpty();
    }

    @Test

    void afterFailureDoesNotChangeSuccess() {
        replies.add(response(text("hello"), "completed"));
        var interceptor = new com.commercefoundry.ai.metering.AiCallInterceptor() {
                    public com.commercefoundry.ai.metering.AiCallHandle before(com.commercefoundry.ai.metering.AiCallContext context) {
                        return null;
                    }

                    public void after(com.commercefoundry.ai.metering.AiCallHandle handle, com.commercefoundry.ai.metering.AiCallOutcome outcome) {
                        throw new IllegalStateException("after-failure");
                    }
                };
        AiClient client = AiClients.create(properties(Map.of()), List.of(interceptor));
        clients.add((AutoCloseable) client);
        assertThat(client.complete(AiRequest.of("chat", null, "hello")).text()).isEqualTo("hello");
    }

    @Test

    void retriesRemainInsideOneProviderAndOneMeteringCall() {
        status = 503;
        replies.add("{\"error\":{\"message\":\"retry-private\",\"type\":\"server_error\"}}");
        replies.add("{\"error\":{\"message\":\"retry-private\",\"type\":\"server_error\"}}");
        AiProperties p = properties(Map.of());
        p =
                new AiProperties(
                        p.enabled(),
                        p.apiKey(),
                        p.baseUrl(),
                        p.defaultModel(),
                        p.defaultMaxTokens(),
                        p.connectTimeout(),
                        p.readTimeout(),
                        p.requestTimeout(),
                        1,
                        p.maxConcurrency(),
                        p.acquireTimeout(),
                        p.refusalFallback(), p.features(), p.masking(), p.maxInputChars(), p.allowInsecureLoopback(), p.allowUnmetered(), p.provider(), p.openai());
        AiClient client = AiClients.create(p, List.of(meter));
        clients.add((AutoCloseable) client);
        assertThatThrownBy(() -> client.complete(AiRequest.of("chat", null, "hello")))
               .isInstanceOfSatisfying(AiException.class, error -> assertThat(error.reason()).isEqualTo(AiException.Reason.UNAVAILABLE));
        assertThat(requests).hasSize(2);
        assertThat(meter.single().status()).isEqualTo(Status.FAILED);
    }

    @Test

    void unknownAndMalformedToolArgumentsAreRejectedAfterUsageCapture() {
        replies.add(
                response(
                        "[{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"unregistered\",\"arguments\":\"{\"}]",
                        "completed"));
        assertThatThrownBy(() -> client().converse(conversation()))
               .isInstanceOfSatisfying(AiException.class, error -> assertThat(error.reason()).isEqualTo(AiException.Reason.INVALID_RESPONSE));
        assertThat(meter.single().status()).isEqualTo(Status.UNUSABLE);
    }

    @Test

    void malformedRegisteredToolArgumentsAreUnusable() {
        AiTool tool =
                new AiTool(
                        "lookup",
                        "lookup",
                        "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"],\"additionalProperties\":false}");
        replies.add(
                response(
                        "[{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"lookup\",\"arguments\":\"{}\"}]",
                        "completed"));
        assertThatThrownBy(() -> client().converse(conversation().withTools(List.of(tool))))
               .isInstanceOfSatisfying(AiException.class, error -> assertThat(error.reason()).isEqualTo(AiException.Reason.INVALID_RESPONSE));
        assertThat(meter.single().status()).isEqualTo(Status.UNUSABLE);
    }

    @Test

    void invalidCacheUsageIsRejected() {
        replies.add(response(text("hello"), "completed").replace("cached_tokens\":4", "cached_tokens\":13"));
        assertThatThrownBy(() -> client().complete(AiRequest.of("chat", null, "hello")))
               .isInstanceOfSatisfying(AiException.class, error -> assertThat(error.reason()).isEqualTo(AiException.Reason.INVALID_RESPONSE));
        assertThat(meter.single().status()).isEqualTo(Status.TIMEOUT);
    }

    @Test

    void piiInJsonKeyFailsBeforeMeteringAndHttp() {
        AiTool tool =
                new AiTool(
                        "lookup",
                        "lookup",
                        "{\"type\":\"object\",\"properties\":{\"member@example.com\":{\"type\":\"string\"}}}");
        assertThatThrownBy(() -> client().converse(conversation().withTools(List.of(tool))))
               .isInstanceOfSatisfying(AiException.class, error -> assertThat(error.reason()).isEqualTo(AiException.Reason.MASKING_FAILED));
        assertThat(requests).isEmpty();
        assertThat(meter.contexts).isEmpty();
    }

    @Test

    void imagesUseInlineDataAndMaskedText() {
        replies.add(response(text("hello"), "completed"));
        client().complete(
                        AiRequest.of("chat", null, "hello")
                               .withImages(
                                        List.of(new AiImage(AiImage.PNG, new byte[] {1, 2, 3}))));
        assertThat(requests.getFirst()).contains("input_image", "data:image/png;base64,AQID");
    }

    record DescribedAnswer(
            @com.fasterxml.jackson.annotation.JsonPropertyDescription("member@example.com")
                    String answer) {}

    record PrivateKeyAnswer(
            @com.fasterxml.jackson.annotation.JsonProperty("member@example.com") String answer) {}

    enum SensitiveChoice {
        CANARY_VALUE
    }

    record EnumAnswer(SensitiveChoice answer) {}

    @Test

    void structuredSchemaDescriptionsAreMaskedAndMetered() {
        replies.add(response(text("{\"answer\":\"yes\"}"), "completed"));
        client().complete(AiRequest.of("chat", null, "prompt"), DescribedAnswer.class, value -> List.of());
        JsonNodeProbe.assertSchemaAccounting(requests.getFirst(), meter.contexts.getFirst().inputChars());
        assertThat(requests.getFirst()).contains("[EMAIL]").doesNotContain("member@example.com");
        assertThat(meter.contexts.getFirst().provider()).isEqualTo("openai");
        assertThat(meter.single().status()).isEqualTo(Status.COMPLETED);
    }

    static class JsonNodeProbe {
        static void assertSchemaAccounting(String request, long actual) {
            var body = AiJson.object(request).orElseThrow();
            assertThat(actual).isEqualTo("prompt".length() + AiJson.write(body.path("text")).length());
        }
    }

    @Test

    void structuredPrivateKeysAndMaskedEnumConstraintsAreRejectedBeforeMetering() {
        assertThatThrownBy(
                        () ->
                                client().complete(
                                                AiRequest.of("chat", null, "prompt"),
                                                PrivateKeyAnswer.class,
                                                value -> List.of()))
               .isInstanceOfSatisfying(AiException.class, failure -> assertThat(failure.reason()).isEqualTo(AiException.Reason.MASKING_FAILED));
        assertThatThrownBy(
                        () ->
                                client().complete(
                                                new AiRequest(
                                                        "chat",
                                                        null,
                                                        "prompt",
                                                        List.of("CANARY_VALUE")),
                                                EnumAnswer.class,
                                                value -> List.of()))
               .isInstanceOfSatisfying(AiException.class, failure -> assertThat(failure.reason()).isEqualTo(AiException.Reason.MASKING_FAILED));
        assertThat(requests).isEmpty();
        assertThat(meter.contexts).isEmpty();
    }

    @Test

    void structuredRawSchemaCountsTowardInputLimitBeforeMaskingAndMetering() {
        AiProperties p = properties(Map.of());
        p =
                new AiProperties(
                        p.enabled(),
                        p.apiKey(),
                        p.baseUrl(),
                        p.defaultModel(),
                        p.defaultMaxTokens(),
                        p.connectTimeout(),
                        p.readTimeout(),
                        p.requestTimeout(),
                        p.maxRetries(),
                        p.maxConcurrency(),
                        p.acquireTimeout(), p.refusalFallback(), p.features(), p.masking(), 20, p.allowInsecureLoopback(), p.allowUnmetered(), p.provider(), p.openai());
        AiClient client = AiClients.create(p, List.of(meter));
        clients.add((AutoCloseable) client);
        assertThatThrownBy(
                        () ->
                                client.complete(
                                        AiRequest.of("chat", null, "prompt"),
                                        DescribedAnswer.class,
                                        value -> List.of()))
               .isInstanceOfSatisfying(AiException.class, failure -> assertThat(failure.reason()).isEqualTo(AiException.Reason.INPUT_TOO_LONG));
        assertThat(requests).isEmpty();
        assertThat(meter.contexts).isEmpty();
    }

    static Map<String, AiException.Reason> errorCodes() {
        return Map.of(
                "server_error",
                AiException.Reason.UNAVAILABLE,
                "rate_limit_exceeded",
                AiException.Reason.RATE_LIMITED,
                "invalid_prompt",
                AiException.Reason.REJECTED,
                "data_residency_mismatch",
                AiException.Reason.REJECTED,
                "bio_policy",
                AiException.Reason.REFUSED,
                "misalignment_policy_violation",
                AiException.Reason.REFUSED,
                "invalid_image_format",
                AiException.Reason.REJECTED, "image_content_policy_violation", AiException.Reason.REFUSED, "future-private-code", AiException.Reason.REJECTED);
    }

    @Test

    void failedResponsesClassifyFixedErrorCodesAndKeepUsageWithoutLeakingMessages() {
        for (var entry : errorCodes().entrySet()) {
            replies.add(
                    response("[]", "failed")
                           .replace(
                                    "\"output\":[]",
                                    "\"output\":[],\"error\":{\"code\":\""
                                            + entry.getKey()
                                            + "\",\"message\":\"private-provider-message\"}"));
            assertThatThrownBy(() -> client().complete(AiRequest.of("chat", null, "prompt")))
                   .isInstanceOfSatisfying(
                            AiException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(entry.getValue()))
                   .hasMessageNotContaining("private-provider-message").hasMessageNotContaining("future-private-code").hasNoCause();
            assertThat(meter.single().status()).isEqualTo(Status.UNUSABLE);
            meter = new RecordingInterceptor();
        }
    }

    @Test

    void streamingErrorsClassifyFixedErrorCodesWithoutLeakingMessages() {
        for (var entry : errorCodes().entrySet()) {
            sse =
                    event(
                            "error",
                            "{\"type\":\"error\",\"sequence_number\":1,\"code\":\""
                                    + entry.getKey()
                                    + "\",\"message\":\"private-provider-message\",\"param\":null}");
            assertThatThrownBy(() -> client().converseStreaming(conversation(), delta -> true))
                   .isInstanceOfSatisfying(
                            AiException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(entry.getValue()))
                   .hasMessageNotContaining("private-provider-message").hasMessageNotContaining("future-private-code").hasNoCause();
            assertThat(meter.single().status()).isEqualTo(Status.TIMEOUT);
            meter = new RecordingInterceptor();
        }
    }

    record Answer(String answer) {}

    @Test

    void structuredUsesStrictSchemaAndValidator() {
        replies.add(response(text("{\"answer\":\"yes\"}"), "completed"));
        assertThat(client().complete(AiRequest.of("chat", null, "prompt"), Answer.class, value -> List.of()).value().answer()).isEqualTo("yes");
        assertThat(requests.getFirst()).contains("json_schema", "\"strict\":true");
        assertThat(meter.contexts.getFirst().provider()).isEqualTo("openai");
        assertThat(meter.single().status()).isEqualTo(Status.COMPLETED);
    }

    @Test

    void toolsUseCallIdAndRoundTripMaskedResults() {
        String call =
                "[{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],\"encrypted_content\":\"opaque\"},"
                    + "{\"type\":\"function_call\",\"id\":\"fc_item\",\"call_id\":\"call_123\",\"name\":\"lookup\",\"arguments\":\"{}\",\"status\":\"completed\"}]";
        replies.add(response(call, "completed"));
        replies.add(response(text("done"), "completed"));
        AiTool tool =
                new AiTool(
                        "lookup",
                        "lookup member@example.com",
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
        AiClient client = client();
        var request = AiConversationRequest.of("chat", null, List.of(AiMessage.user("lookup")), List.of(tool));
        var result = client.converse(request);
        assertThat(result.toolCalls().getFirst().id()).isEqualTo("call_123");
        client.converse(request.append(result.assistantMessage(), AiMessage.toolResults(List.of(AiContent.toolResult("call_123", "member@example.com")))));
        assertThat(requests.get(1)).contains("function_call_output", "call_123", "opaque", "[EMAIL]").doesNotContain("member@example.com", "fc_item");
        assertThat(meter.outcomes).hasSize(2);
    }

    @Test

    void streamingOnlyTextAndRequiresTerminal() {
        sse =
                event(
                                "response.output_text.delta",
                                "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\",\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"sequence_number\":1}")
                        + event(
                                "response.completed",
                                "{\"type\":\"response.completed\",\"sequence_number\":2,\"response\":"
                                        + response(text("hello"), "completed")
                                        + "}");
        List<String> deltas = new ArrayList<>();
        var result = client().converseStreaming(AiConversationRequest.of("chat", null, List.of(AiMessage.user("hello"))), delta -> {
                                    deltas.add(delta);
                                    return true;
                                });
        assertThat(deltas).containsExactly("hello");
        assertThat(result.text()).isEqualTo("hello");
        assertThat(meter.contexts.getFirst().provider()).isEqualTo("openai");
        assertThat(meter.single().status()).isEqualTo(Status.COMPLETED);
    }

    @Test

    void startCancellationSendsNothing() {
        var result = client().converseStreaming(AiConversationRequest.of("chat", null, List.of(AiMessage.user("hello"))), new AiStreamListener() {
                                    public void onStart(AiStreamHandle handle) {
                                        handle.cancel();
                                    }

                                    public boolean onTextDelta(String delta) {
                                        return true;
                                    }
                                });
        assertThat(result.cancelled()).isTrue();
        assertThat(result.assistantMessage()).isNull();
        assertThat(result.usage()).isEqualTo(AiUsage.ZERO);
        assertThat(requests).isEmpty();
        assertThat(meter.single().status()).isEqualTo(Status.NOT_SENT);
    }

    @Test

    void refusalIsChargedButUnusable() {
        replies.add(
                response(
                        "[{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"secret"
                            + " refusal\"}]}]",
                        "completed"));
        assertThatThrownBy(() -> client().complete(AiRequest.of("chat", null, "prompt")))
               .isInstanceOf(AiException.class)
               .satisfies(error -> assertThat(((AiException) error).reason()).isEqualTo(AiException.Reason.REFUSED)).hasMessageNotContaining("secret");
        assertThat(meter.single().status()).isEqualTo(Status.UNUSABLE);
    }

    @Test

    void httpErrorsAreSanitizedWithoutFallback() {
        status = 401;
        replies.add(
                "{\"error\":{\"message\":\"private-body\",\"type\":\"invalid_request_error\"}}");
        assertThatThrownBy(() -> client().complete(AiRequest.of("chat", null, "prompt"))).isInstanceOf(AiException.class).hasMessageNotContaining("private-body").hasNoCause();
        assertThat(requests).hasSize(1);
        assertThat(meter.single().reason()).isEqualTo(AiException.Reason.UNAUTHORIZED);
    }
}
