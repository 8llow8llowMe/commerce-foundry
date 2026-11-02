package com.commercefoundry.ai.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Messages API 를 흉내 내는 루프백 HTTP 서버 — 실제 API 대신 SDK 의 요청 · 재시도 · 응답 해석을 그대로 거친다.
 * 응답을 차례로 쌓아 두고, 받은 요청(헤더 · 본문)을 기록한다. 쌓인 응답이 없으면 마지막 응답을 되풀이한다.
 * 도구 호출 응답({@link #okToolUse}) · 임의 내용 블록({@link #okContent}) · SSE 스트리밍({@link #stream}, 이벤트 사이 간격 · 끝 정지 · {@code message_stop} 생략으로
 * 끊김 · 타임아웃을 흉내 낸다)을 낼 수 있다. 클라이언트가 스트림을 먼저 닫으면 {@link #abortedStreams()} 가 오른다.
 *
 * <p>ai-core 테스트 픽스처다 — 다른 모듈은 {@code testImplementation testFixtures(project(':core:ai-core'))} 로 받는다. 클라이언트는
 * {@code ai.base-url=}{@link #baseUrl()} 과 {@code ai.allow-insecure-loopback=true} 로 이 서버를 가리킨다. 픽스처 값은 가짜다.
 */
public final class StubMessagesServer implements AutoCloseable {

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Reply last;
    private final AtomicInteger abortedStreams = new AtomicInteger();
    private final AtomicInteger completedStreams = new AtomicInteger();

    public StubMessagesServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/messages", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public StubMessagesServer reply(Reply reply) {
        replies.add(reply);
        return this;
    }

    public List<Received> received() {
        return received;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** 성공 응답. 사용량은 입력 12 · 출력 5 토큰이고 캐시 토큰은 없다. */
    public static Reply ok(String text, String stopReason) {
        return okWithUsage(text, stopReason, "{\"input_tokens\":12,\"output_tokens\":5}");
    }

    /** 캐시 토큰까지 실은 성공 응답. */
    public static Reply ok(String text, String stopReason, long inputTokens, long outputTokens, long cacheCreationInputTokens, long cacheReadInputTokens) {
        return okWithUsage(text, stopReason, "{\"input_tokens\":%d,\"output_tokens\":%d,\"cache_creation_input_tokens\":%d,\"cache_read_input_tokens\":%d}"
            .formatted(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens));
    }

    /** 오류 응답. {@code retry-after-ms: 1} 을 실어 SDK 재시도가 기다리지 않게 한다. */
    public static Reply error(int status, String type) {
        return new Reply(status, """
            {"type":"error","error":{"type":"%s","message":"stub error body"}}""".formatted(type), Map.of("retry-after-ms", "1"), 0, null);
    }

    private static Reply okWithUsage(String text, String stopReason, String usage) {
        return okContent("[{\"type\":\"text\",\"text\":%s}]".formatted(quote(text)), stopReason, usage);
    }

    /**
     * 내용 블록을 직접 쓰는 성공 응답 — 도구 호출 · thinking 블록을 섞을 때 쓴다.
     *
     * @param contentJson {@code content} 배열 JSON
     * @param usageJson   {@code usage} 객체 JSON
     */
    public static Reply okContent(String contentJson, String stopReason, String usageJson) {
        return new Reply(200, """
            {"id":"msg_stub","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":%s,
             "stop_reason":"%s","stop_sequence":null,
             "usage":%s}""".formatted(contentJson, stopReason, usageJson), Map.of(), 0, null);
    }

    /** 도구 하나를 부르는 응답({@code stop_reason=tool_use}). 앞에 짧은 텍스트를 둔다. 사용량은 입력 30 · 출력 9 토큰. */
    public static Reply okToolUse(String id, String name, String inputJson) {
        return okContent("""
            [{"type":"text","text":"조회해 볼게요."},{"type":"tool_use","id":"%s","name":"%s","input":%s}]""".formatted(id, name, inputJson),
            "tool_use", "{\"input_tokens\":30,\"output_tokens\":9}");
    }

    /** {@code text} 블록 JSON. */
    public static String textBlock(String text) {
        return "{\"type\":\"text\",\"text\":%s}".formatted(quote(text));
    }

    /** 사용량 JSON. */
    public static String usage(long inputTokens, long outputTokens) {
        return "{\"input_tokens\":%d,\"output_tokens\":%d}".formatted(inputTokens, outputTokens);
    }

    // ---------------------------------------------------------------- 스트리밍(SSE)

    /**
     * SSE 스트리밍 응답. 이벤트 사이에 {@code gapMillis} 를 쉰다. 마지막 이벤트 뒤 {@code stallMillis} 를 쉰 뒤 연결을 닫는다 —
     * {@code message_stop} 을 빼면 중간 끊김, 앞쪽에서 오래 쉬면 읽기 타임아웃을 흉내 낸다.
     */
    public static Reply stream(List<String> events, long gapMillis, long stallMillis) {
        return new Reply(200, "", Map.of(), 0, null, List.copyOf(events), gapMillis, stallMillis);
    }

    /** 텍스트 조각을 차례로 흘리는 완전한 스트림(message_start → 블록 → message_delta → message_stop). */
    public static List<String> textStream(List<String> deltas, String stopReason, long inputTokens, long outputTokens) {
        List<String> events = new ArrayList<>();
        events.add(messageStart(inputTokens));
        events.add(blockStart(0, "{\"type\":\"text\",\"text\":\"\"}"));
        deltas.forEach(delta -> events.add(textDelta(0, delta)));
        events.add(blockStop(0));
        events.add(messageDelta(stopReason, outputTokens));
        events.add(messageStop());
        return events;
    }

    public static String messageStart(long inputTokens) {
        return sse("message_start", """
            {"type":"message_start","message":{"id":"msg_stream","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],
             "stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":%d,"output_tokens":1,"cache_read_input_tokens":0}}}""".formatted(inputTokens));
    }

    public static String blockStart(int index, String contentBlockJson) {
        return sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":%d,\"content_block\":%s}".formatted(index, contentBlockJson));
    }

    public static String textDelta(int index, String text) {
        return sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":%d,\"delta\":{\"type\":\"text_delta\",\"text\":%s}}".formatted(index, quote(text)));
    }

    public static String inputJsonDelta(int index, String partialJson) {
        return sse("content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":%d,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":%s}}".formatted(index, quote(partialJson)));
    }

    public static String blockStop(int index) {
        return sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":%d}".formatted(index));
    }

    public static String messageDelta(String stopReason, long outputTokens) {
        return sse("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"%s\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":%d}}"
            .formatted(stopReason, outputTokens));
    }

    public static String messageStop() {
        return sse("message_stop", "{\"type\":\"message_stop\"}");
    }

    public static String ping() {
        return sse("ping", "{\"type\":\"ping\"}");
    }

    /** 스트림 중 오류 이벤트(HTTP 200 뒤). */
    public static String errorEvent(String type) {
        return sse("error", "{\"type\":\"error\",\"error\":{\"type\":\"%s\",\"message\":\"stub stream error\"}}".formatted(type));
    }

    private static String sse(String event, String data) {
        // data 는 한 줄이어야 한다 — 여러 줄 JSON 이면 SSE 가 줄마다 다른 data 로 읽는다
        return "event: " + event + "\ndata: " + data.replace("\n", "") + "\n\n";
    }

    /** 스트림을 쓰다 클라이언트가 먼저 닫아 쓰기가 실패한 횟수 — 클라이언트가 스트림을 닫았는지(자원 해제) 본다. */
    public int abortedStreams() {
        return abortedStreams.get();
    }

    /** 끝까지 다 쓴 스트림 수. */
    public int completedStreams() {
        return completedStreams.get();
    }

    /**
     * 쌓아 둘 응답 하나.
     *
     * @param delayMillis 응답 전에 기다리는 시간 — 응답 타임아웃을 흉내 낸다
     * @param hold        풀릴 때까지(최대 10초) 응답을 붙잡는다 — 동시 상한을 채운다
     * @param events      SSE 이벤트들. 있으면 {@code body} 대신 스트림으로 보낸다
     * @param gapMillis   SSE 이벤트 사이 간격
     * @param stallMillis 마지막 SSE 이벤트 뒤 닫기 전에 쉬는 시간
     */
    public record Reply(int status, String body, Map<String, String> headers, long delayMillis, CountDownLatch hold, List<String> events, long gapMillis,
                        long stallMillis) {

        public Reply(int status, String body, Map<String, String> headers, long delayMillis, CountDownLatch hold) {
            this(status, body, headers, delayMillis, hold, null, 0, 0);
        }

        public Reply delayed(long millis) {
            return new Reply(status, body, headers, millis, hold, events, gapMillis, stallMillis);
        }

        public Reply heldUntil(CountDownLatch latch) {
            return new Reply(status, body, headers, delayMillis, latch, events, gapMillis, stallMillis);
        }
    }

    /** 받은 요청 하나. */
    public record Received(Map<String, List<String>> headers, String body) {

        public String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> String.join(",", entry.getValue())).findFirst().orElse(null);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(Map.copyOf(exchange.getRequestHeaders()), body));
        Reply reply = replies.poll();
        if (reply == null) {
            reply = last;
        }
        last = reply;
        try {
            if (reply.hold() != null) {
                reply.hold().await(10, TimeUnit.SECONDS);
            }
            if (reply.delayMillis() > 0) {
                Thread.sleep(reply.delayMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (reply.events() != null) {
            writeStream(exchange, reply);
            return;
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        try {
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException ignored) {
            // 클라이언트가 타임아웃으로 끊었다
        }
    }

    /** 이벤트마다 쓰고 비운다(chunked). 클라이언트가 닫았으면 쓰기가 실패한다 — 그 횟수를 센다. */
    private void writeStream(HttpExchange exchange, Reply reply) {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        try {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            for (String event : reply.events()) {
                out.write(event.getBytes(StandardCharsets.UTF_8));
                out.flush();
                pause(reply.gapMillis());
            }
            pause(reply.stallMillis());
            out.close();
            completedStreams.incrementAndGet();
        } catch (IOException e) {
            abortedStreams.incrementAndGet();
            exchange.close();
        }
    }

    private static void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
