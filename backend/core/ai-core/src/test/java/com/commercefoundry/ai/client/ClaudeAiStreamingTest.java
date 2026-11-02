package com.commercefoundry.ai.client;

import static com.commercefoundry.ai.support.StubMessagesServer.blockStart;
import static com.commercefoundry.ai.support.StubMessagesServer.blockStop;
import static com.commercefoundry.ai.support.StubMessagesServer.error;
import static com.commercefoundry.ai.support.StubMessagesServer.errorEvent;
import static com.commercefoundry.ai.support.StubMessagesServer.inputJsonDelta;
import static com.commercefoundry.ai.support.StubMessagesServer.messageDelta;
import static com.commercefoundry.ai.support.StubMessagesServer.messageStart;
import static com.commercefoundry.ai.support.StubMessagesServer.messageStop;
import static com.commercefoundry.ai.support.StubMessagesServer.ping;
import static com.commercefoundry.ai.support.StubMessagesServer.stream;
import static com.commercefoundry.ai.support.StubMessagesServer.textDelta;
import static com.commercefoundry.ai.support.StubMessagesServer.textStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.ai.client.AiException.Reason;
import com.commercefoundry.ai.metering.AiCallOutcome;
import com.commercefoundry.ai.metering.AiCallOutcome.Status;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.ai.support.StubMessagesServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 스트리밍 대화를 루프백 SSE 스텁으로 본다 — 델타 순서 · 최종 결과 · 취소 · 끊김 · 타임아웃 · 계량 (#467). 픽스처 값은 가짜다. */
@ExtendWith(OutputCaptureExtension.class)
class ClaudeAiStreamingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AiTool LOOKUP = new AiTool("lookup_order", "주문 번호로 주문 상태를 조회한다",
        "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}");
    private static final AiConversationRequest REQUEST = AiConversationRequest.of("cs-chat", "규칙", List.of(AiMessage.user("배송 언제 와요?")), List.of(LOOKUP));

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
    @DisplayName("텍스트 델타가 순서대로 오고 최종 결과 · 사용량(message_start 입력 + message_delta 출력)이 맞다 — 계량 COMPLETED 한 번")
    void deltasInOrder(CapturedOutput output) throws Exception {
        server.reply(stream(textStream(List.of("내일 ", "도착 ", "예정입니다"), "end_turn", 25, 11), 5, 0));
        RecordingInterceptor interceptor = new RecordingInterceptor();
        List<String> deltas = new CopyOnWriteArrayList<>();

        AiConversationResult result = client(interceptor, Duration.ofSeconds(5)).converseStreaming(REQUEST, deltas::add);

        assertThat(deltas).containsExactly("내일 ", "도착 ", "예정입니다");
        assertThat(result.text()).isEqualTo("내일 도착 예정입니다");
        assertThat(result.cancelled()).isFalse();
        assertThat(result.stopReason()).isEqualTo(AiStopReason.END_TURN);
        assertThat(result.messageId()).isEqualTo("msg_stream");
        assertThat(result.usage()).isEqualTo(new AiUsage(25, 11, 0, 0));
        assertThat(JSON.readTree(server.received().getFirst().body()).get("stream").asBoolean()).isTrue();
        AiCallOutcome outcome = interceptor.single();
        assertThat(outcome.status()).isEqualTo(Status.COMPLETED);
        assertThat(outcome.usage()).isEqualTo(new AiUsage(25, 11, 0, 0));
        assertThat(outcome.messageId()).isEqualTo("msg_stream");
        assertThat(interceptor.contexts.getFirst().streaming()).isTrue();
        assertThat(server.completedStreams()).isEqualTo(1);
        assertThat(output.getAll()).contains("streaming=true").doesNotContain("예정입니다");
    }

    @Test
    @DisplayName("스트림으로 온 도구 호출은 조립해 돌려주고, listener 에는 텍스트 델타만 간다")
    void toolCallAssembledFromStream() throws Exception {
        server.reply(stream(List.of(
            messageStart(30),
            blockStart(0, "{\"type\":\"text\",\"text\":\"\"}"),
            textDelta(0, "조회할게요"),
            blockStop(0),
            ping(),
            blockStart(1, "{\"type\":\"tool_use\",\"id\":\"toolu_s1\",\"name\":\"lookup_order\",\"input\":{}}"),
            inputJsonDelta(1, "{\"orderId\":"),
            inputJsonDelta(1, "\"42\"}"),
            blockStop(1),
            messageDelta("tool_use", 20),
            messageStop()), 0, 0));
        RecordingInterceptor interceptor = new RecordingInterceptor();
        List<String> deltas = new ArrayList<>();

        AiConversationResult result = client(interceptor, Duration.ofSeconds(5)).converseStreaming(REQUEST, deltas::add);

        assertThat(deltas).containsExactly("조회할게요");
        assertThat(result.stopReason()).isEqualTo(AiStopReason.TOOL_USE);
        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("toolu_s1");
            assertThat(call.name()).isEqualTo("lookup_order");
            assertThat(JSON.readTree(call.argumentsJson()).get("orderId").asText()).isEqualTo("42");
        });
        assertThat(result.usage()).isEqualTo(new AiUsage(30, 20, 0, 0));
        assertThat(interceptor.single().status()).isEqualTo(Status.COMPLETED);
    }

    @Test
    @DisplayName("스트림으로 온 도구 인자가 required 를 빠뜨리면 INVALID_RESPONSE · UNUSABLE 실측이다")
    void invalidStreamedToolArguments() {
        server.reply(stream(List.of(
            messageStart(30),
            blockStart(0, "{\"type\":\"tool_use\",\"id\":\"toolu_s2\",\"name\":\"lookup_order\",\"input\":{}}"),
            inputJsonDelta(0, "{}"),
            blockStop(0),
            messageDelta("tool_use", 4),
            messageStop()), 0, 0));
        RecordingInterceptor interceptor = new RecordingInterceptor();

        assertThatThrownBy(() -> client(interceptor, Duration.ofSeconds(5)).converseStreaming(REQUEST, delta -> true))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE));
        AiCallOutcome outcome = interceptor.single();
        assertThat(outcome.status()).isEqualTo(Status.UNUSABLE);
        assertThat(outcome.usage()).isEqualTo(new AiUsage(30, 4, 0, 0));
    }

    @Test
    @DisplayName("listener 가 false 를 돌려주면 스트림을 닫고 cancelled 결과다 — 받은 사용량이 있어 UNUSABLE(원인 없음), 서버 쪽 쓰기가 끊긴다")
    void listenerCancels() throws Exception {
        List<String> events = new ArrayList<>(List.of(messageStart(18), blockStart(0, "{\"type\":\"text\",\"text\":\"\"}")));
        for (int i = 0; i < 40; i++) {
            events.add(textDelta(0, "조각" + i + " "));
        }
        events.addAll(List.of(blockStop(0), messageDelta("end_turn", 80), messageStop()));
        server.reply(stream(events, 50, 0));
        RecordingInterceptor interceptor = new RecordingInterceptor();
        List<String> deltas = new ArrayList<>();

        long startedAt = System.nanoTime();
        AiConversationResult result = client(interceptor, Duration.ofSeconds(5)).converseStreaming(REQUEST, delta -> {
            deltas.add(delta);
            return deltas.size() < 2;
        });
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(result.cancelled()).isTrue();
        assertThat(result.text()).isEqualTo("조각0 조각1 ");
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.assistantMessage()).isNull();
        assertThat(result.usage()).isEqualTo(new AiUsage(18, 1, 0, 0));
        // 40 조각 × 50ms = 2초를 다 읽지 않았다
        assertThat(elapsedMs).isLessThan(1_500);
        AiCallOutcome outcome = interceptor.single();
        assertThat(outcome.status()).isEqualTo(Status.UNUSABLE);
        assertThat(outcome.reason()).isNull();
        assertThat(outcome.usage()).isEqualTo(new AiUsage(18, 1, 0, 0));
        assertThat(outcome.messageId()).isEqualTo("msg_stream");
        awaitAborted();
    }

    @Test
    @DisplayName("다른 스레드의 핸들 취소는 막힌 읽기가 다음 바이트 · 끊김으로 풀리는 즉시 cancelled 로 끝난다(끊김 오류가 아니다) — 동시 상한 자리도 풀린다")
    void handleCancelsBlockedRead() {
        // 취소 뒤 서버가 1.5초 쉬고 message_stop 없이 닫는다 — 취소하지 않았다면 UNAVAILABLE 이다
        server.reply(stream(List.of(messageStart(9), blockStart(0, "{\"type\":\"text\",\"text\":\"\"}"), textDelta(0, "잠시만요")), 0, 1_500));
        RecordingInterceptor interceptor = new RecordingInterceptor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicReference<AiStreamHandle> handle = new AtomicReference<>();
        AiClient client = AiClients.create(properties(Duration.ofSeconds(10), 1), List.of(interceptor));
        try {
            long startedAt = System.nanoTime();
            AiConversationResult result = client.converseStreaming(REQUEST, new AiStreamListener() {
                @Override
                public boolean onTextDelta(String delta) {
                    scheduler.schedule(() -> handle.get().cancel(), 200, TimeUnit.MILLISECONDS);
                    return true;
                }

                @Override
                public void onStart(AiStreamHandle started) {
                    handle.set(started);
                }
            });
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertThat(result.cancelled()).isTrue();
            assertThat(result.text()).isEqualTo("잠시만요");
            assertThat(handle.get().cancelled()).isTrue();
            // SDK 가 OkHttp Call.cancel 을 드러내지 않아 막힌 읽기는 다음 바이트 · 서버 닫힘 · 읽기 타임아웃까지 기다린다(stall 1.5초)
            assertThat(elapsedMs).isLessThan(3_500);
            AiCallOutcome outcome = interceptor.single();
            assertThat(outcome.status()).isEqualTo(Status.UNUSABLE);
            assertThat(outcome.reason()).isNull();
        } finally {
            scheduler.shutdownNow();
        }
        // 동시 상한 1 — 자리가 풀렸으면 다음 호출이 BUSY 없이 간다
        server.reply(stream(textStream(List.of("네"), "end_turn", 5, 1), 0, 0));
        assertThat(client.converseStreaming(REQUEST, delta -> true).text()).isEqualTo("네");
    }

    @Test
    @DisplayName("message_stop 없이 끊기면 UNAVAILABLE — message_start 를 받았으면 UNUSABLE 실측, 못 받았으면 TIMEOUT")
    void disconnectMidStream() {
        server.reply(stream(List.of(messageStart(14), blockStart(0, "{\"type\":\"text\",\"text\":\"\"}"), textDelta(0, "도중에")), 0, 0))
            .reply(stream(List.of(ping()), 0, 0));
        RecordingInterceptor interceptor = new RecordingInterceptor();
        AiClient client = client(interceptor, Duration.ofSeconds(5));
        List<String> deltas = new ArrayList<>();

        assertThatThrownBy(() -> client.converseStreaming(REQUEST, deltas::add))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE));
        assertThatThrownBy(() -> client.converseStreaming(REQUEST, deltas::add))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE));

        assertThat(deltas).containsExactly("도중에");
        assertThat(interceptor.outcomes).extracting(AiCallOutcome::status).containsExactly(Status.UNUSABLE, Status.TIMEOUT);
        assertThat(interceptor.outcomes.get(0).usage()).isEqualTo(new AiUsage(14, 1, 0, 0));
        assertThat(interceptor.outcomes.get(1).usage()).isNull();
        assertThat(interceptor.handles).containsExactlyElementsOf(interceptor.issued);
    }

    @Test
    @DisplayName("스트림 중 읽기 타임아웃은 UNAVAILABLE 이고 스트림을 닫는다 — 받은 사용량이 있어 UNUSABLE")
    void readTimeoutMidStream() {
        server.reply(stream(List.of(messageStart(16), blockStart(0, "{\"type\":\"text\",\"text\":\"\"}"), textDelta(0, "생각 중")), 0, 3_000));
        RecordingInterceptor interceptor = new RecordingInterceptor();

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client(interceptor, Duration.ofMillis(400)).converseStreaming(REQUEST, delta -> true))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(elapsedMs).isLessThan(2_500);
        AiCallOutcome outcome = interceptor.single();
        assertThat(outcome.status()).isEqualTo(Status.UNUSABLE);
        assertThat(outcome.reason()).isEqualTo(Reason.UNAVAILABLE);
        assertThat(outcome.usage()).isEqualTo(new AiUsage(16, 1, 0, 0));
    }

    @Test
    @DisplayName("스트림 중 오류 이벤트는 오류 종류로 번역한다(overloaded → UNAVAILABLE), 시작 전 오류 응답은 FAILED · 상태 코드")
    void errorsAreNormalized() {
        server.reply(stream(List.of(messageStart(12), errorEvent("overloaded_error")), 0, 0)).reply(error(529, "overloaded_error"));
        RecordingInterceptor interceptor = new RecordingInterceptor();
        AiClient client = client(interceptor, Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.converseStreaming(REQUEST, delta -> true))
            .isInstanceOfSatisfying(AiException.class, e -> {
                assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE);
                assertThat(e.errorType()).isEqualTo("overloaded_error");
                assertThat(e.getCause()).isNull();
                assertThat(e.getMessage()).doesNotContain("stub stream error");
            });
        assertThatThrownBy(() -> client.converseStreaming(REQUEST, delta -> true))
            .isInstanceOfSatisfying(AiException.class, e -> {
                assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE);
                assertThat(e.statusCode()).isEqualTo(529);
            });

        assertThat(interceptor.outcomes).extracting(AiCallOutcome::status).containsExactly(Status.UNUSABLE, Status.FAILED);
        assertThat(interceptor.outcomes.get(1).statusCode()).isEqualTo(529);
        assertThat(interceptor.outcomes.get(1).usage()).isNull();
    }

    @Test
    @DisplayName("listener 가 던진 예외는 스트림을 닫고 계량을 마친 뒤 그대로 나간다")
    void listenerExceptionPropagates() {
        server.reply(stream(textStream(List.of("a", "b"), "end_turn", 7, 2), 0, 0));
        RecordingInterceptor interceptor = new RecordingInterceptor();
        IllegalStateException boom = new IllegalStateException("caller failure");

        assertThatThrownBy(() -> client(interceptor, Duration.ofSeconds(5)).converseStreaming(REQUEST, delta -> {
            throw boom;
        })).isSameAs(boom);

        AiCallOutcome outcome = interceptor.single();
        assertThat(outcome.status()).isEqualTo(Status.UNUSABLE);
        assertThat(outcome.usage()).isEqualTo(new AiUsage(7, 1, 0, 0));
    }

    @Test
    @DisplayName("before 가 막으면 HTTP · onStart · after 가 없다")
    void beforeRejectsStreaming() {
        RecordingInterceptor interceptor = new RecordingInterceptor(context -> {
            throw new AiException(Reason.BUDGET_EXCEEDED, context.feature());
        });
        List<AiStreamHandle> started = new ArrayList<>();

        assertThatThrownBy(() -> client(interceptor, Duration.ofSeconds(5)).converseStreaming(REQUEST, new AiStreamListener() {
            @Override
            public boolean onTextDelta(String delta) {
                return true;
            }

            @Override
            public void onStart(AiStreamHandle handle) {
                started.add(handle);
            }
        })).isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BUDGET_EXCEEDED));
        assertThat(server.received()).isEmpty();
        assertThat(started).isEmpty();
        assertThat(interceptor.outcomes).isEmpty();
    }

    @Test
    @DisplayName("message_start 전에 다른 스레드가 취소하면 CANCELLED 결과이고 원장은 TIMEOUT · 원인 null(사용량 없음)")
    void cancelBeforeMessageStart() {
        server.reply(stream(List.of(ping()), 0, 1_000));
        RecordingInterceptor interceptor = new RecordingInterceptor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AiConversationResult result = client(interceptor, Duration.ofSeconds(5)).converseStreaming(REQUEST, new AiStreamListener() {
                @Override
                public boolean onTextDelta(String delta) {
                    return true;
                }

                @Override
                public void onStart(AiStreamHandle handle) {
                    scheduler.schedule(handle::cancel, 200, TimeUnit.MILLISECONDS);
                }
            });

            assertThat(result.stopReason()).isEqualTo(AiStopReason.CANCELLED);
            assertThat(result.text()).isEmpty();
            assertThat(result.usage()).isEqualTo(AiUsage.ZERO);
            AiCallOutcome outcome = interceptor.single();
            assertThat(outcome.status()).isEqualTo(Status.TIMEOUT);
            assertThat(outcome.reason()).isNull();
            assertThat(outcome.usage()).isNull();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("message_start 전에 온 오류 이벤트는 FAILED 이고 상태 코드가 없다")
    void errorEventBeforeMessageStart() {
        server.reply(stream(List.of(errorEvent("rate_limit_error")), 0, 0));
        RecordingInterceptor interceptor = new RecordingInterceptor();

        assertThatThrownBy(() -> client(interceptor, Duration.ofSeconds(5)).converseStreaming(REQUEST, delta -> true))
            .isInstanceOfSatisfying(AiException.class, e -> {
                assertThat(e.reason()).isEqualTo(Reason.RATE_LIMITED);
                assertThat(e.statusCode()).isNull();
            });
        AiCallOutcome outcome = interceptor.single();
        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.statusCode()).isNull();
        assertThat(outcome.usage()).isNull();
    }

    @Test
    @DisplayName("onStart 가 던지거나 onStart 에서 바로 취소하면 보내지 않는다 — 원장 NOT_SENT · 원인 null")
    void onStartFailureOrCancelIsNotSent() {
        RecordingInterceptor interceptor = new RecordingInterceptor();
        AiClient client = client(interceptor, Duration.ofSeconds(5));
        IllegalStateException boom = new IllegalStateException("caller failure");

        assertThatThrownBy(() -> client.converseStreaming(REQUEST, new AiStreamListener() {
            @Override
            public boolean onTextDelta(String delta) {
                return true;
            }

            @Override
            public void onStart(AiStreamHandle handle) {
                throw boom;
            }
        })).isSameAs(boom);
        AiConversationResult cancelled = client.converseStreaming(REQUEST, new AiStreamListener() {
            @Override
            public boolean onTextDelta(String delta) {
                return true;
            }

            @Override
            public void onStart(AiStreamHandle handle) {
                handle.cancel();
            }
        });

        assertThat(cancelled.cancelled()).isTrue();
        assertThat(server.received()).isEmpty();
        assertThat(interceptor.outcomes).extracting(AiCallOutcome::status).containsExactly(Status.NOT_SENT, Status.NOT_SENT);
        assertThat(interceptor.outcomes).extracting(AiCallOutcome::reason).containsOnlyNulls();
        assertThat(interceptor.outcomes).extracting(AiCallOutcome::usage).containsOnlyNulls();
    }

    @Test
    @DisplayName("listener 예외 뒤에도 동시 상한 자리가 풀린다 — 상한 1 에서 다음 호출이 BUSY 없이 간다")
    void listenerExceptionReleasesPermit() {
        server.reply(stream(textStream(List.of("a"), "end_turn", 7, 2), 0, 0)).reply(stream(textStream(List.of("b"), "end_turn", 7, 2), 0, 0));
        RecordingInterceptor interceptor = new RecordingInterceptor();
        AiClient client = AiClients.create(properties(Duration.ofSeconds(5), 1), List.of(interceptor));

        assertThatThrownBy(() -> client.converseStreaming(REQUEST, delta -> {
            throw new IllegalStateException("caller failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(client.converseStreaming(REQUEST, delta -> true).text()).isEqualTo("b");

        assertThat(interceptor.outcomes).extracting(AiCallOutcome::status).containsExactly(Status.UNUSABLE, Status.COMPLETED);
        assertThat(interceptor.outcomes.getFirst().reason()).isNull();
    }

    private void awaitAborted() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.abortedStreams() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(server.abortedStreams()).isEqualTo(1);
        assertThat(server.completedStreams()).isZero();
    }

    private AiClient client(RecordingInterceptor interceptor, Duration readTimeout) {
        return AiClients.create(properties(readTimeout, 4), List.of(interceptor));
    }

    private AiProperties properties(Duration readTimeout, int maxConcurrency) {
        return new AiProperties(true, "test-key", server.baseUrl(), null, null, Duration.ofSeconds(2), readTimeout, Duration.ofSeconds(10), 0,
            maxConcurrency, Duration.ofMillis(300), false, Map.of(), null, null, true, false);
    }
}
