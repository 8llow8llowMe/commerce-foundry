package com.commercefoundry.domainlayer.aiusage.adapter.in.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.commercefoundry.ai.client.AiClient;
import com.commercefoundry.ai.client.AiConversationRequest;
import com.commercefoundry.ai.client.AiException;
import com.commercefoundry.ai.client.AiMessage;
import com.commercefoundry.ai.client.AiRequest;
import com.commercefoundry.ai.client.AiStreamHandle;
import com.commercefoundry.ai.client.AiStreamListener;
import com.commercefoundry.ai.client.AiTool;
import com.commercefoundry.ai.support.StubMessagesServer;
import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageReserveCommand;
import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageSettleCommand;
import com.commercefoundry.domainlayer.aiusage.application.port.in.AiUsageMeterUseCase;
import com.commercefoundry.domainlayer.aiusage.application.port.out.AiUsageCallRepositoryPort;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiTokenUsage;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageStatus;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real SDK -> common metering -> H2 ledger. No paid API is called. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:operations-openai-ledger;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
        + "INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "operations.jobs.worker.enabled=false", "ai.enabled=true", "ai.provider=openai", "ai.openai.api-key=stub-key", "ai.openai.model=test-small",
    "ai.api-key=stub-key", "ai.default-model=test-small", "ai.features.legacy.provider=anthropic", "ai.allow-insecure-loopback=true",
    "operations.ai.pricing.providers.openai.models.test-small.input=2", "operations.ai.pricing.providers.openai.models.test-small.output=10",
    "operations.ai.pricing.providers.openai.models.test-small.cache-write=2.5", "operations.ai.pricing.providers.openai.models.test-small.cache-read=0.2",
    "ai.default-max-tokens=100", "ai.connect-timeout=2s", "ai.read-timeout=2s", "ai.request-timeout=3s", "ai.max-retries=0"
})
@ActiveProfiles("test")
class OpenAiUsageMeteringFlowTest {
    private static final Queue<Reply> REPLIES = new ConcurrentLinkedQueue<>();
    private static final HttpServer SERVER = start();
    private static final StubMessagesServer CLAUDE = new StubMessagesServer();
    @Autowired private AiClient client;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AiUsageMeterUseCase meter;
    @Autowired private AiUsageCallRepositoryPort calls;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/responses", exchange -> {
                exchange.getRequestBody().readAllBytes();
                Reply reply = REPLIES.remove();
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", reply.streaming() ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start();
            return server;
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("ai.openai.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("ai.base-url", CLAUDE::baseUrl);
    }
    @AfterAll static void stop() { SERVER.stop(0); CLAUDE.close(); }
    private record Reply(String body, boolean streaming) { }
    private static String response(String output) {
        return "{\"id\":\"resp_stub\",\"object\":\"response\",\"model\":\"test-small\",\"status\":\"completed\",\"output\":" + output
            + ",\"usage\":{\"input_tokens\":12,\"input_tokens_details\":{\"cached_tokens\":4},\"output_tokens\":5}}";
    }
    private static String message(String escapedText) {
        return "[{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\""
            + escapedText + "\",\"annotations\":[]}]}]";
    }
    private Map<String,Object> row(String caller) {
        return jdbc.queryForMap("select * from operations.ai_usage_call where caller_ref = ?", caller);
    }
    private void actual(String caller, String status) {
        Map<String,Object> row = row(caller);
        assertThat(row.get("provider")).isEqualTo("openai");
        assertThat(row.get("requested_model")).isEqualTo("test-small");
        assertThat(row.get("model")).isEqualTo("test-small");
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(((Number) row.get("input_tokens")).longValue()).isEqualTo(8L);
        assertThat(((Number) row.get("cache_read_input_tokens")).longValue()).isEqualTo(4L);
        assertThat(((Number) row.get("cache_creation_input_tokens")).longValue()).isZero();
        assertThat(((Number) row.get("output_tokens")).longValue()).isEqualTo(5L);
        assertThat(((Number) row.get("cost_micros")).longValue()).isEqualTo(67L);
    }
    @Test void textAndSameModelAnthropicCallsHaveDistinctProviders() {
        REPLIES.add(new Reply(response(message("hello")), false));
        client.complete(AiRequest.of("text", null, "prompt").withCallerRef("job:201"));
        actual("job:201", "COMPLETED");
        CLAUDE.reply(StubMessagesServer.ok("hello", "end_turn"));
        client.complete(AiRequest.of("legacy", null, "prompt").withCallerRef("job:202"));
        assertThat(row("job:202").get("provider")).isEqualTo("anthropic");
        assertThat(row("job:202").get("requested_model")).isEqualTo("test-small");
    }
    record Answer(String answer) { }
    @Test void structuredCallHasProviderAndExactCost() {
        REPLIES.add(new Reply(response(message("{\\\"answer\\\":\\\"yes\\\"}")), false));
        assertThat(client.complete(AiRequest.of("structured", null, "prompt").withCallerRef("job:203"), Answer.class, value -> List.of()).value().answer())
            .isEqualTo("yes");
        actual("job:203", "COMPLETED");
        assertThat(row("job:203").get("structured")).isEqualTo(true);
    }
    private AiConversationRequest conversation(String caller) {
        return AiConversationRequest.of("chat", null, List.of(AiMessage.user("prompt"))).withCallerRef(caller);
    }
    @Test void toolConversationHasProviderAndExactCost() {
        String output = "[{\"type\":\"function_call\",\"id\":\"fc_item\",\"call_id\":\"call_123\",\"name\":\"lookup\",\"arguments\":\"{}\"}]";
        REPLIES.add(new Reply(response(output), false));
        var result = client.converse(conversation("job:204").withTools(List.of(new AiTool("lookup", "lookup", "{\"type\":\"object\",\"properties\":{}}"))));
        assertThat(result.toolCalls().getFirst().id()).isEqualTo("call_123");
        actual("job:204", "COMPLETED");
    }
    @Test void streamingCallHasProviderAndExactCost() {
        String completed = "{\"type\":\"response.completed\",\"sequence_number\":1,\"response\":" + response(message("hello")) + "}";
        REPLIES.add(new Reply("event: response.completed\ndata: " + completed + "\n\n", true));
        assertThat(client.converseStreaming(conversation("job:205"), delta -> true).text()).isEqualTo("hello");
        actual("job:205", "COMPLETED");
    }
    @Test void refusedResponseStillRecordsProviderAndChargedUsage() {
        String output = "[{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"no\"}]}]";
        REPLIES.add(new Reply(response(output), false));
        assertThatThrownBy(() -> client.complete(AiRequest.of("refused", null, "prompt").withCallerRef("job:206")))
            .isInstanceOfSatisfying(AiException.class, error -> assertThat(error.reason()).isEqualTo(AiException.Reason.REFUSED));
        actual("job:206", "UNUSABLE");
    }
    @Test void cancellationBeforeSendingRecordsProviderAndZeroCost() {
        var result = client.converseStreaming(conversation("job:207"), new AiStreamListener() {
            @Override public void onStart(AiStreamHandle handle) { handle.cancel(); }
            @Override public boolean onTextDelta(String delta) { return true; }
        });
        assertThat(result.cancelled()).isTrue();
        Map<String,Object> row = row("job:207");
        assertThat(row.get("provider")).isEqualTo("openai");
        assertThat(row.get("status")).isEqualTo("NOT_SENT");
        assertThat(((Number) row.get("cost_micros")).longValue()).isZero();
    }
    @Test void sameAliasUsesReservationProviderForExactSettlementCost() {
        for (String provider : List.of("anthropic", "openai")) {
            var reservation = meter.reserve(new AiUsageReserveCommand("same-alias", null, "job:" + provider, "test-small", 100, 10, 0, false, provider));
            assertThat(reservation.estimateCostMicros()).isEqualTo("openai".equals(provider) ? 1010L : 505L);
            meter.settle(new AiUsageSettleCommand(reservation.callId(), AiUsageStatus.COMPLETED, new AiTokenUsage(8,5,0,4), "test-small", null, null, "msg", 1));
            Map<String,Object> row = row("job:" + provider);
            assertThat(row.get("provider")).isEqualTo(provider);
            assertThat(((Number)row.get("cost_micros")).longValue()).isEqualTo("openai".equals(provider) ? 67L : 34L);
        }
    }

    @Test void legacyNullProviderAlsoSurvivesAbandonmentAndCannotBeInsertedAsNew() {
        var reserved = meter.reserve(new AiUsageReserveCommand("legacy", null, "job:209", "test-small", 100, 10, 0, false, "openai"));
        jdbc.update("update operations.ai_usage_call set provider = null where id = ?", reserved.callId());
        var legacy = calls.findById(reserved.callId()).orElseThrow();
        assertThatThrownBy(() -> calls.insert(legacy)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("provider");
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status ->
            assertThat(calls.closeIfPending(legacy.abandon(java.time.Instant.now()))).isTrue());
        assertThat(row("job:209").get("provider")).isNull();
        assertThat(row("job:209").get("status")).isEqualTo("ABANDONED");
    }

    @Test void legacyNullProviderLoadsAndRemainsNullAfterSettlement() {
        var reserved = meter.reserve(new AiUsageReserveCommand("legacy", null, "job:208", "test-small", 100, 10, 0, false));
        jdbc.update("update operations.ai_usage_call set provider = null where id = ?", reserved.callId());
        assertThat(calls.findById(reserved.callId()).orElseThrow().provider()).isNull();
        meter.settle(new AiUsageSettleCommand(reserved.callId(), AiUsageStatus.COMPLETED, new AiTokenUsage(8,5,0,4), "test-small", null, null, "msg", 1));
        assertThat(row("job:208").get("provider")).isNull();
        assertThat(((Number)row("job:208").get("cost_micros")).longValue()).isEqualTo(34L);
    }
}
